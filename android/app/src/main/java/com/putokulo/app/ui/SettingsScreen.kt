package com.putokulo.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.putokulo.app.crash.CrashAlertManager
import com.putokulo.app.crash.CrashDetector
import com.putokulo.app.data.EmergencyContact
import com.putokulo.app.data.SettingsRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val riderName by SettingsRepository.riderName.collectAsStateWithLifecycle()
    val contacts by SettingsRepository.contacts.collectAsStateWithLifecycle()
    val helmetAddress by SettingsRepository.helmetAddress.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    val ignoringBatteryLimits = rememberIgnoringBatteryOptimizations()

    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let { uri -> readPickedContact(context, uri)?.let(SettingsRepository::addContact) }
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings") })
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionTitle("Rider") }
            item {
                OutlinedTextField(
                    value = riderName,
                    onValueChange = SettingsRepository::setRiderName,
                    label = { Text("Your name") },
                    supportingText = { Text("Used in the alert SMS") },
                    leadingIcon = { Icon(Icons.Outlined.Person, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item { SectionTitle("Emergency contacts") }
            if (contacts.isEmpty()) {
                item {
                    Text(
                        "No contacts yet. Add at least one person who should get an SMS if you crash.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            items(contacts) { contact ->
                Card {
                    ListItem(
                        headlineContent = { Text(contact.name) },
                        supportingContent = { Text(contact.phone) },
                        leadingContent = { Icon(Icons.Filled.Contacts, null) },
                        trailingContent = {
                            IconButton(onClick = { SettingsRepository.removeContact(contact) }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Remove ${contact.name}")
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showAddDialog = true }) {
                        Icon(Icons.Filled.Add, null, Modifier.size(ButtonDefaults.IconSize))
                        Text("Add contact", Modifier.padding(start = 8.dp))
                    }
                    OutlinedButton(onClick = {
                        pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                    }) {
                        Text("From phonebook")
                    }
                }
            }

            item { SectionTitle("Helmet camera") }
            item {
                OutlinedTextField(
                    value = helmetAddress,
                    onValueChange = SettingsRepository::setHelmetAddress,
                    label = { Text("Helmet address") },
                    placeholder = { Text("Find automatically") },
                    supportingText = {
                        Text("Leave empty to find the helmet on the same Wi-Fi or hotspot. Example: 192.168.43.20:8000")
                    },
                    leadingIcon = { Icon(Icons.Outlined.Videocam, null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item { SectionTitle("Crash detection") }
            item {
                Text(
                    "While ride protection is on, a hard impact (over ${CrashDetector.IMPACT_G.toInt()}g) " +
                        "when you were riding faster than ${CrashDetector.MOVING_KMH.toInt()} km/h, followed by " +
                        "the phone lying still, starts a ${CrashAlertManager.COUNTDOWN_SECONDS}-second countdown. " +
                        "If you don't cancel it, an SMS with your location is sent to your contacts.\n\n" +
                        "Keep your phone on you, like in a jacket or pants pocket, not in a bag.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedButton(onClick = { CrashAlertManager.trigger(context, isTest = true) }) {
                    Icon(Icons.Filled.NotificationsActive, null, Modifier.size(ButtonDefaults.IconSize))
                    Text("Test crash alert (no SMS)", Modifier.padding(start = 8.dp))
                }
            }
            if (!ignoringBatteryLimits) {
                item {
                    OutlinedButton(onClick = { requestIgnoreBatteryOptimizations(context) }) {
                        Icon(Icons.Filled.BatteryAlert, null, Modifier.size(ButtonDefaults.IconSize))
                        Text("Allow running in background", Modifier.padding(start = 8.dp))
                    }
                }
                item {
                    Text(
                        "Some phones stop apps in the background to save battery. Allow this so crash detection keeps running during long rides.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddContactDialog(
            onAdd = {
                SettingsRepository.addContact(it)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun AddContactDialog(onAdd: (EmergencyContact) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add emergency contact") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    phone,
                    { phone = it },
                    label = { Text("Mobile number") },
                    placeholder = { Text("09XX XXX XXXX") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(EmergencyContact(name.trim(), phone.trim())) },
                enabled = name.isNotBlank() && phone.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Picking a phone number grants temporary read access to that one entry, so no contacts permission is needed. */
private fun readPickedContact(context: Context, uri: Uri): EmergencyContact? {
    val projection = arrayOf(
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.NUMBER,
    )
    return context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) EmergencyContact(cursor.getString(0), cursor.getString(1)) else null
    }
}

/** Re-checked whenever the screen resumes, e.g. after returning from the system dialog. */
@Composable
private fun rememberIgnoringBatteryOptimizations(): Boolean {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    var ignoring by remember { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) ignoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return ignoring
}

@SuppressLint("BatteryLife") // a safety app is an accepted use of this request
private fun requestIgnoreBatteryOptimizations(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    )
}
