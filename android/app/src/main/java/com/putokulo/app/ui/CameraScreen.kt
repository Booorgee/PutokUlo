package com.putokulo.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.putokulo.app.data.SettingsRepository
import com.putokulo.app.helmet.HelmetClient
import com.putokulo.app.helmet.HelmetStatus
import com.putokulo.app.helmet.ProximityReadings
import com.putokulo.app.ui.theme.AlertRed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private val ClearGreen = Color(0xFF2E7D32)
private val WarnAmber = Color(0xFFF9A825)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manualAddress by SettingsRepository.helmetAddress.collectAsStateWithLifecycle()
    var discoveredAddress by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(manualAddress) {
        if (manualAddress.isBlank()) HelmetClient.discover(context).collect { discoveredAddress = it }
    }
    val address = manualAddress.trim().ifBlank { discoveredAddress }

    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Helmet camera") })
        if (address == null) {
            SearchingForHelmet()
        } else {
            HelmetFeed(address)
        }
    }
}

@Composable
private fun SearchingForHelmet() {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        VideoBox {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                Spacer(Modifier.height(12.dp))
                Text("Looking for your helmet…", color = Color.White)
            }
        }
        Text(
            "1. Turn on your phone's hotspot (or join the same Wi-Fi as the helmet).\n" +
                "2. Power on the helmet and start camera_server.py.\n" +
                "3. It connects automatically. If it doesn't, enter the helmet's address in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HelmetFeed(address: String) {
    var frame by remember(address) { mutableStateOf<ImageBitmap?>(null) }
    var connectionError by remember(address) { mutableStateOf<String?>(null) }

    // Reconnect forever: the helmet may be switched off, rebooting, or out of hotspot range
    LaunchedEffect(address) {
        while (true) {
            try {
                HelmetClient.frames(address).collect {
                    frame = it.asImageBitmap()
                    connectionError = null
                }
                connectionError = "The stream stopped"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                connectionError = "Can't reach the helmet at $address"
            }
            frame = null
            delay(2_000)
        }
    }
    val status by produceState<HelmetStatus?>(null, address) {
        HelmetClient.status(address).collect { value = it }
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Match the video's shape (4:3 on the Pi camera) so there are no black bars
        VideoBox(aspectRatio = frame?.let { it.width.toFloat() / it.height } ?: (4f / 3f)) {
            val current = frame
            if (current != null) {
                Image(current, contentDescription = "Helmet camera", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                LiveBadge(status?.fps, Modifier.align(Alignment.TopStart).padding(8.dp))
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        connectionError?.let { "$it. Retrying…" } ?: "Connecting to $address…",
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        ProximityCard(status?.proximity, helmetReachable = status != null)
    }
}

@Composable
private fun VideoBox(aspectRatio: Float = 4f / 3f, content: @Composable BoxScope.() -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .clip(MaterialTheme.shapes.medium)
            .background(Color.Black),
        content = content,
    )
}

@Composable
private fun LiveBadge(fps: Float?, modifier: Modifier = Modifier) {
    Surface(color = Color.Black.copy(alpha = 0.6f), contentColor = Color.White, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Box(Modifier.size(8.dp).background(AlertRed, CircleShape))
            Spacer(Modifier.width(6.dp))
            Text(
                if (fps != null && fps > 0) "LIVE · %.0f fps".format(fps) else "LIVE",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private enum class Level { Unknown, Clear, Warn, Danger }

private fun levelOf(cm: Float?, readings: ProximityReadings?) = when {
    readings == null -> Level.Unknown
    cm == null -> Level.Clear
    cm < readings.solidBelowCm -> Level.Danger
    cm < readings.warnBelowCm -> Level.Warn
    else -> Level.Clear
}

@Composable
private fun Level.color() = when (this) {
    Level.Unknown -> MaterialTheme.colorScheme.outlineVariant
    Level.Clear -> ClearGreen
    Level.Warn -> WarnAmber
    Level.Danger -> AlertRed
}

private val SIDE_PHRASES = mapOf(
    "front" to "in front of you",
    "back" to "behind you",
    "left" to "on your left",
    "right" to "on your right",
)

@Composable
private fun ProximityCard(readings: ProximityReadings?, helmetReachable: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Proximity sensors", style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            ProximitySummary(readings, helmetReachable)
            Spacer(Modifier.height(8.dp))
            HelmetDiagram(readings)
        }
    }
}

@Composable
private fun ProximitySummary(readings: ProximityReadings?, helmetReachable: Boolean) {
    val nearest = readings?.distances
        ?.filterValues { it != null && it < readings.warnBelowCm }
        ?.minByOrNull { it.value!! }

    val (text, level) = when {
        !helmetReachable -> "Not connected to the helmet" to Level.Unknown
        readings == null -> "Sensors are off. Start proximity.py on the helmet." to Level.Unknown
        nearest == null -> "All clear" to Level.Clear
        else -> {
            val cm = nearest.value!!
            val where = SIDE_PHRASES[nearest.key] ?: nearest.key
            val level = levelOf(cm, readings)
            val prefix = if (level == Level.Danger) "Very close $where" else "Something $where"
            "$prefix · ${cm.toInt()} cm" to level
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (level == Level.Danger || level == Level.Warn) {
            Icon(Icons.Filled.Warning, null, tint = level.color(), modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (level == Level.Danger) FontWeight.Bold else FontWeight.Normal,
            color = if (level == Level.Danger) AlertRed else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Top-down view of the helmet with each sensor's side colored by how close something is. */
@Composable
private fun HelmetDiagram(readings: ProximityReadings?) {
    val sides = listOf("front", "right", "back", "left")
    val colors = sides.associateWith { levelOf(readings?.distances?.get(it), readings).color() }
    val helmetColor = MaterialTheme.colorScheme.surfaceVariant
    val visorColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(Modifier.size(240.dp)) {
        Canvas(
            Modifier
                .size(150.dp)
                .align(Alignment.Center),
        ) {
            val stroke = 14.dp.toPx()
            val arcInset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val arcTopLeft = Offset(arcInset, arcInset)
            // Compose angles start at 3 o'clock and go clockwise; each side gets a 76° arc
            val centerAngles = mapOf("front" to -90f, "right" to 0f, "back" to 90f, "left" to 180f)
            for (side in sides) {
                drawArc(
                    color = colors.getValue(side),
                    startAngle = centerAngles.getValue(side) - 38f,
                    sweepAngle = 76f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
            drawCircle(helmetColor, radius = size.minDimension * 0.3f)
            // Visor marks the front of the helmet
            drawArc(
                color = visorColor,
                startAngle = -140f,
                sweepAngle = 100f,
                useCenter = false,
                topLeft = center - Offset(size.minDimension * 0.22f, size.minDimension * 0.22f),
                size = Size(size.minDimension * 0.44f, size.minDimension * 0.44f),
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        SideLabel("Front", readings?.distances?.get("front"), readings, Modifier.align(Alignment.TopCenter))
        SideLabel("Back", readings?.distances?.get("back"), readings, Modifier.align(Alignment.BottomCenter))
        SideLabel("Left", readings?.distances?.get("left"), readings, Modifier.align(Alignment.CenterStart))
        SideLabel("Right", readings?.distances?.get("right"), readings, Modifier.align(Alignment.CenterEnd))
    }
}

@Composable
private fun SideLabel(name: String, cm: Float?, readings: ProximityReadings?, modifier: Modifier) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier) {
        Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            when {
                readings == null -> "–"
                cm == null -> "clear"
                else -> "${cm.toInt()} cm"
            },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = levelOf(cm, readings).color().takeIf { readings != null } ?: MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
