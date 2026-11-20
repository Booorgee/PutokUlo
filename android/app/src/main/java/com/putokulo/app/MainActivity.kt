package com.putokulo.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.putokulo.app.crash.CrashAlertManager
import com.putokulo.app.ui.AlertActivity
import com.putokulo.app.ui.CameraScreen
import com.putokulo.app.ui.MapScreen
import com.putokulo.app.ui.SettingsScreen
import com.putokulo.app.ui.theme.PutokUloTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PutokUloTheme { MainScreen() }
        }

        // While the app is open, show the countdown screen as soon as a crash is detected
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CrashAlertManager.state
                    .map { it is CrashAlertManager.State.Countdown }
                    .distinctUntilChanged()
                    .collect { counting ->
                        if (counting) startActivity(Intent(this@MainActivity, AlertActivity::class.java))
                    }
            }
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    Map("Map", Icons.Filled.Map),
    Camera("Camera", Icons.Filled.Videocam),
    Settings("Settings", Icons.Filled.Settings),
}

@Composable
private fun MainScreen() {
    var selected by rememberSaveable { mutableIntStateOf(Tab.Map.ordinal) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selected == tab.ordinal,
                        onClick = { selected = tab.ordinal },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        // Only the bottom bar is padded: the map draws behind the status bar and each screen
        // handles its own top inset
        val content = Modifier.padding(bottom = padding.calculateBottomPadding())
        when (Tab.entries[selected]) {
            Tab.Map -> MapScreen(content)
            Tab.Camera -> CameraScreen(content)
            Tab.Settings -> SettingsScreen(content)
        }
    }
}

fun Context.hasPermission(permission: String): Boolean {
    // Notification permission only exists from Android 13; before that it's always allowed
    if (permission == Manifest.permission.POST_NOTIFICATIONS && Build.VERSION.SDK_INT < 33) return true
    return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}
