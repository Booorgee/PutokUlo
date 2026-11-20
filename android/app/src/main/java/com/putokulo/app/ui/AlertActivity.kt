package com.putokulo.app.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.putokulo.app.crash.CrashAlertManager
import com.putokulo.app.crash.CrashAlertManager.State
import com.putokulo.app.ui.theme.AlertRed
import com.putokulo.app.ui.theme.AlertRedDark
import com.putokulo.app.ui.theme.PutokUloTheme

/** Full-screen crash countdown. Opens over the lock screen. */
class AlertActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        setContent {
            PutokUloTheme {
                val state by CrashAlertManager.state.collectAsStateWithLifecycle()
                LaunchedEffect(state) { if (state == State.Idle) finish() }
                AlertScreen(
                    state = state,
                    onCancel = { CrashAlertManager.cancel(this) },
                    onDismiss = { CrashAlertManager.dismiss(this) },
                )
            }
        }
    }
}

@Composable
fun AlertScreen(state: State, onCancel: () -> Unit, onDismiss: () -> Unit) {
    when (state) {
        is State.Countdown -> CountdownContent(state, onCancel)
        is State.Sent -> SentContent(state, onDismiss)
        State.Idle -> Box(Modifier.fillMaxSize())
    }
}

@Composable
private fun CountdownContent(state: State.Countdown, onCancel: () -> Unit) {
    // Back shouldn't quietly hide a countdown that is about to send an SMS
    BackHandler {}

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .background(AlertRed)
            .systemBarsPadding()
            .padding(24.dp),
    ) {
        if (state.isTest) TestBadge()
        Spacer(Modifier.weight(1f))

        Icon(Icons.Filled.Warning, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(
            "Possible crash detected",
            color = Color.White,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))

        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { state.secondsLeft / CrashAlertManager.COUNTDOWN_SECONDS.toFloat() },
                modifier = Modifier.size(200.dp),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.25f),
                strokeWidth = 12.dp,
                strokeCap = StrokeCap.Round,
            )
            Text("${state.secondsLeft}", color = Color.White, fontSize = 80.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(32.dp))

        Text(
            if (state.contacts.isEmpty()) {
                "No emergency contacts are set, so no one can be alerted."
            } else {
                "Sending your location to ${state.contacts.joinToString { it.name }}"
            },
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.weight(1f))
        Button(
            onClick = onCancel,
            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = AlertRedDark),
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
        ) {
            Text("I'M OK — CANCEL", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SentContent(state: State.Sent, onDismiss: () -> Unit) {
    val failed = !state.isTest && state.sentTo.isEmpty()
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .systemBarsPadding()
                .padding(24.dp),
        ) {
            if (state.isTest) TestBadge(onRed = false)
            Spacer(Modifier.weight(1f))
            Icon(
                if (failed) Icons.Filled.ErrorOutline else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp),
            )
            Text(
                when {
                    failed -> "Alert could not be sent"
                    state.isTest -> "Test complete"
                    else -> "Alert sent"
                },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                when {
                    failed -> "Check that SMS permission is allowed and the phone has signal. Call for help if you can."
                    state.isTest -> "In a real crash, this SMS would go to ${state.sentTo.joinToString { it.name }.ifEmpty { "your contacts (none set yet)" }}:"
                    else -> "Your location was sent to ${state.sentTo.joinToString { it.name }}:"
                },
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            if (!failed) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text("Close", fontSize = 18.sp)
            }
        }
    }
}

@Composable
private fun TestBadge(onRed: Boolean = true) {
    Surface(
        color = if (onRed) Color.White.copy(alpha = 0.2f) else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (onRed) Color.White else MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            "TEST · no SMS will be sent",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
