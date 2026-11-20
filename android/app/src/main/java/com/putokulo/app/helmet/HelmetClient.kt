package com.putokulo.app.helmet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL

/** Matches DISCOVERY_PORT in pi/common.py. */
private const val DISCOVERY_PORT = 50000

data class ProximityReadings(
    /** Sensor name ("front", "back", "left", "right") to distance in cm, or null when nothing is in range. */
    val distances: Map<String, Float?>,
    val warnBelowCm: Float,
    val solidBelowCm: Float,
)

/** Latest /status from the helmet. [proximity] is null when proximity.py isn't running. */
data class HelmetStatus(val fps: Float, val proximity: ProximityReadings?)

object HelmetClient {

    /**
     * Listens for the "PUTOKULO <port>" broadcasts from camera_server.py and emits "ip:port"
     * whenever a helmet is heard.
     */
    fun discover(context: Context): Flow<String> = flow {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        // Some phones drop broadcast packets to save battery unless an app asks for them
        val lock = wifi.createMulticastLock("PutokUlo:discovery").apply { setReferenceCounted(false); acquire() }
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = 1_000
            bind(InetSocketAddress(DISCOVERY_PORT))
        }
        try {
            val buffer = ByteArray(64)
            while (currentCoroutineContext().isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue // re-check for cancellation
                }
                val parts = String(packet.data, 0, packet.length).trim().split(" ")
                val port = parts.getOrNull(1)?.toIntOrNull()
                if (parts.firstOrNull() == "PUTOKULO" && port != null) {
                    emit("${packet.address.hostAddress}:$port")
                }
            }
        } finally {
            socket.close()
            lock.release()
        }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    /** Decodes the helmet's MJPEG stream into frames. Throws when the connection fails or drops. */
    fun frames(address: String): Flow<Bitmap> = flow {
        val connection = (URL("http://$address/stream.mjpg").openConnection() as HttpURLConnection).apply {
            connectTimeout = 3_000
            readTimeout = 5_000
        }
        try {
            val input = DataInputStream(BufferedInputStream(connection.inputStream))
            while (currentCoroutineContext().isActive) {
                // Each part is "--FRAME", headers, a blank line, then Content-Length bytes of JPEG
                var length = -1
                while (true) {
                    val line = input.readAsciiLine() ?: return@flow
                    if (line.startsWith("Content-Length", ignoreCase = true)) {
                        length = line.substringAfter(':').trim().toInt()
                    } else if (line.isEmpty() && length >= 0) {
                        break
                    }
                }
                val jpeg = ByteArray(length)
                input.readFully(jpeg)
                BitmapFactory.decodeByteArray(jpeg, 0, length)?.let { emit(it) }
            }
        } finally {
            connection.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    /** Polls /status a few times a second. Emits null while the helmet can't be reached. */
    fun status(address: String): Flow<HelmetStatus?> = flow {
        while (currentCoroutineContext().isActive) {
            emit(runCatching { fetchStatus(address) }.getOrNull())
            delay(300)
        }
    }.flowOn(Dispatchers.IO)

    private fun fetchStatus(address: String): HelmetStatus {
        val connection = (URL("http://$address/status").openConnection() as HttpURLConnection).apply {
            connectTimeout = 2_000
            readTimeout = 2_000
        }
        try {
            val json = JSONObject(connection.inputStream.bufferedReader().readText())
            val proximity = json.optJSONObject("proximity")?.let { p ->
                val readings = p.getJSONObject("readings")
                ProximityReadings(
                    distances = readings.keys().asSequence().associateWith { name ->
                        if (readings.isNull(name)) null else readings.getDouble(name).toFloat()
                    },
                    warnBelowCm = p.optDouble("warn_below_cm", 200.0).toFloat(),
                    solidBelowCm = p.optDouble("solid_below_cm", 50.0).toFloat(),
                )
            }
            return HelmetStatus(fps = json.optDouble("fps", 0.0).toFloat(), proximity = proximity)
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readAsciiLine(): String? {
        val line = StringBuilder()
        while (true) {
            when (val byte = read()) {
                -1 -> return if (line.isEmpty()) null else line.toString()
                '\n'.code -> return line.toString().trimEnd('\r')
                else -> line.append(byte.toChar())
            }
        }
    }
}
