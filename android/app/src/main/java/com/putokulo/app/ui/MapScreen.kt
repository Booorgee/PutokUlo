package com.putokulo.app.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.putokulo.app.crash.RideProtectionService
import com.putokulo.app.crash.RideState
import com.putokulo.app.data.SettingsRepository
import com.putokulo.app.hasPermission
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

private val MANILA = GeoPoint(14.5995, 120.9842)

private val RIDE_PERMISSIONS = buildList {
    add(Manifest.permission.ACCESS_FINE_LOCATION)
    add(Manifest.permission.ACCESS_COARSE_LOCATION)
    add(Manifest.permission.SEND_SMS)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val running by RideState.running.collectAsStateWithLifecycle()
    val speedKmh by RideState.speedKmh.collectAsStateWithLifecycle()
    val contacts by SettingsRepository.contacts.collectAsStateWithLifecycle()

    var hasLocation by remember { mutableStateOf(context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) }
    var startAfterPermissions by remember { mutableStateOf(false) }
    var recenterRequests by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasLocation = context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        if (startAfterPermissions && hasLocation) RideProtectionService.start(context)
        startAfterPermissions = false
    }

    fun setRideProtection(on: Boolean) {
        if (!on) {
            RideProtectionService.stop(context)
        } else if (RIDE_PERMISSIONS.all { context.hasPermission(it) }) {
            RideProtectionService.start(context)
        } else {
            startAfterPermissions = true
            permissionLauncher.launch(RIDE_PERMISSIONS)
        }
    }

    Box(modifier.fillMaxSize()) {
        OsmMap(showMyLocation = hasLocation, recenterRequests = recenterRequests)

        RideProtectionCard(
            running = running,
            speedKmh = speedKmh,
            contactCount = contacts.size,
            hasLocation = hasLocation,
            onToggle = ::setRideProtection,
            onAllowLocation = {
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(12.dp),
        )

        if (hasLocation) {
            SmallFloatingActionButton(
                onClick = { recenterRequests++ },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = "Center on my location")
            }
        }

        // Required attribution for OpenStreetMap data
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
            shape = MaterialTheme.shapes.extraSmall,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
        ) {
            Text(
                "© OpenStreetMap contributors",
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun RideProtectionCard(
    running: Boolean,
    speedKmh: Float?,
    contactCount: Int,
    hasLocation: Boolean,
    onToggle: (Boolean) -> Unit,
    onAllowLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = running, role = Role.Switch, onValueChange = onToggle),
            ) {
                Icon(
                    if (running) Icons.Filled.Shield else Icons.Outlined.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Ride protection", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (running) "On · watching for crashes" else "Off · turn on before you ride",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = running, onCheckedChange = null)
            }

            if (running || contactCount == 0 || !hasLocation) {
                Spacer(Modifier.size(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (running) {
                        AssistChip(
                            onClick = {},
                            label = { Text(speedKmh?.let { "%.0f km/h".format(it) } ?: "Waiting for GPS") },
                            leadingIcon = { Icon(Icons.Filled.Speed, null, Modifier.size(AssistChipDefaults.IconSize)) },
                        )
                    }
                    if (contactCount > 0) {
                        AssistChip(
                            onClick = {},
                            label = { Text(if (contactCount == 1) "1 contact" else "$contactCount contacts") },
                            leadingIcon = { Icon(Icons.Filled.Contacts, null, Modifier.size(AssistChipDefaults.IconSize)) },
                        )
                    }
                }
                if (contactCount == 0) {
                    Text(
                        "No emergency contacts yet. Add them in Settings so an alert can be sent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (!hasLocation) {
                    TextButton(onClick = onAllowLocation, modifier = Modifier.padding(top = 4.dp)) {
                        Text("Allow location to see where you are")
                    }
                }
            }
        }
    }
}

@Composable
private fun OsmMap(showMyLocation: Boolean, recenterRequests: Int) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(17.0)
            controller.setCenter(MANILA)
        }
    }
    val myLocation = remember {
        MyLocationNewOverlay(GpsMyLocationProvider(context), mapView).also {
            val dot = locationDot(context.resources.displayMetrics.density)
            it.setDirectionArrow(dot, dot)
            it.setPersonAnchor(0.5f, 0.5f)
            it.setDirectionAnchor(0.5f, 0.5f)
            mapView.overlays.add(it)
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            myLocation.disableMyLocation()
            mapView.onDetach()
        }
    }

    LaunchedEffect(showMyLocation) {
        if (showMyLocation) {
            myLocation.enableMyLocation()
            myLocation.enableFollowLocation()
        }
    }

    LaunchedEffect(recenterRequests) {
        if (recenterRequests > 0) {
            myLocation.myLocation?.let { mapView.controller.animateTo(it) }
            myLocation.enableFollowLocation()
        }
    }

    AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
}

/** Blue "you are here" dot with a white ring; osmdroid's default arrow is hard to see on the map. */
private fun locationDot(density: Float): Bitmap {
    val size = (28 * density).toInt()
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val center = size / 2f
    paint.color = android.graphics.Color.argb(60, 15, 91, 167)
    canvas.drawCircle(center, center, center, paint)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(center, center, center * 0.6f, paint)
    paint.color = android.graphics.Color.rgb(15, 91, 167)
    canvas.drawCircle(center, center, center * 0.45f, paint)
    return bitmap
}
