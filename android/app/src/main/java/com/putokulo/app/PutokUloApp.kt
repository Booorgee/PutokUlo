package com.putokulo.app

import android.app.Application
import com.putokulo.app.crash.Notifications
import com.putokulo.app.data.SettingsRepository
import org.osmdroid.config.Configuration
import java.io.File

class PutokUloApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsRepository.init(this)
        Notifications.createChannels(this)

        Configuration.getInstance().apply {
            load(this@PutokUloApp, getSharedPreferences("osmdroid", MODE_PRIVATE))
            // OpenStreetMap's tile servers require apps to identify themselves
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(osmdroidBasePath, "tiles")
        }
    }
}
