package dev.screenoff.recorder.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.screenoff.core.CameraFacing
import dev.screenoff.core.RecMode
import dev.screenoff.core.RecState
import dev.screenoff.recorder.Graph
import dev.screenoff.recorder.RecorderBus
import dev.screenoff.recorder.triggers.Permissions
import dev.screenoff.recorder.triggers.Triggers
import kotlinx.coroutines.delay

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val status by RecorderBus.status.collectAsStateWithLifecycle()
    val config by Graph.config.config.collectAsStateWithLifecycle()
    val segments by Graph.index.segments.collectAsStateWithLifecycle()

    var message by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var ignoringBattery by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    // Re-check the battery optimization setting whenever the user comes back to the app.
    LifecycleResumeEffect(Unit) {
        ignoringBattery = isIgnoringBatteryOptimizations(context)
        onPauseOrDispose { }
    }

    // Tick once per second while a segment is being recorded.
    LaunchedEffect(status.state, status.segmentStartedAtMs) {
        if (status.state == RecState.RECORDING) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1000L)
            }
        }
    }

    val doStart: () -> Unit = {
        val ok = Triggers.start(context, Graph.config.config.value.mode)
        message = if (ok) null else "Could not start recording. Check permissions and try again."
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.all { it }) {
            doStart()
        } else {
            message = "Recording needs the requested permissions."
        }
    }

    val running = status.state != RecState.IDLE
    val recording = status.state == RecState.RECORDING
    val recColor = Color(0xFFD32F2F)
    val armedColor = Color(0xFFEF6C00)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Status
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when (status.state) {
                    RecState.RECORDING -> recColor
                    RecState.ARMED -> armedColor
                    else -> MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (running && status.state != RecState.FINALIZING) Color.White
                else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = when (status.state) {
                        RecState.IDLE -> "Ready"
                        RecState.ARMED -> "Watching for movement"
                        RecState.RECORDING -> "Recording"
                        RecState.FINALIZING -> "Saving the clip"
                    },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val startedAt = status.segmentStartedAtMs
                if (recording && startedAt != null) {
                    Text(formatDuration(now - startedAt), style = MaterialTheme.typography.displaySmall)
                }
                Text(
                    text = when (status.state) {
                        RecState.IDLE -> "You can turn the screen off after you start."
                        RecState.ARMED -> "A clip starts as soon as something moves."
                        RecState.RECORDING -> "You can turn the screen off. Recording continues."
                        RecState.FINALIZING -> "One moment."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Live movement meter, motion mode only
        if (status.mode == RecMode.MOTION && (status.state == RecState.ARMED || recording)) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Movement", style = MaterialTheme.typography.titleMedium)
                    if (status.motionFrames == 0) {
                        Text("Waiting for the camera", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        val needed = status.motionNeeded.coerceAtLeast(1)
                        val level = (status.motionChanged.toFloat() / (needed * 2f)).coerceIn(0f, 1f)
                        val over = status.motionChanged >= needed
                        LinearProgressIndicator(
                            progress = { level },
                            modifier = Modifier.fillMaxWidth().height(10.dp),
                            color = if (over) recColor else MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            if (over) "Movement seen" else "Nothing moving. The bar turns red at the halfway mark.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Level ${status.motionChanged}, needs ${status.motionNeeded}. Camera images: ${status.motionFrames}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        val error = status.lastError
        if (error != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Recording stopped",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(error, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }

        // The one big button
        Surface(
            onClick = {
                if (running) {
                    Triggers.stop(context)
                } else {
                    message = null
                    val missing = Permissions.missing(context, config)
                    if (missing.isEmpty()) doStart() else permissionLauncher.launch(missing.toTypedArray())
                }
            },
            modifier = Modifier.size(132.dp),
            shape = CircleShape,
            color = if (running) MaterialTheme.colorScheme.inverseSurface else recColor,
            contentColor = Color.White,
            shadowElevation = 6.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    if (running) "STOP" else "START",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        val msg = message
        if (msg != null) {
            Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        if (recording) {
            OutlinedButton(onClick = { Triggers.lockCurrent(context) }, modifier = Modifier.fillMaxWidth()) {
                Text("Keep this clip (never delete it automatically)")
            }
        }

        // What to record
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (running) "Mode (stop to change)" else "What should it record?",
                style = MaterialTheme.typography.titleMedium,
            )
            ModeCard(
                title = "Everything",
                description = "Records non-stop until you press Stop.",
                selected = config.mode == RecMode.CONTINUOUS,
                enabled = !running,
                onClick = { Graph.config.update { it.copy(mode = RecMode.CONTINUOUS) } },
            )
            ModeCard(
                title = "Only when something moves",
                description = "Security camera. Watches quietly and saves a clip when it sees movement.",
                selected = config.mode == RecMode.MOTION,
                enabled = !running,
                onClick = { Graph.config.update { it.copy(mode = RecMode.MOTION) } },
            )
        }

        // Current setup at a glance
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Current setup", style = MaterialTheme.typography.titleMedium)
                Text(
                    "${if (config.cameraFacing == CameraFacing.BACK) "Back" else "Front"} camera, " +
                        "${config.quality.name}, sound ${if (config.audioEnabled) "on" else "off"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    if (config.segmentLengthSec <= 0) "One single file until you press Stop, no time limit."
                    else "New file every ${formatMinutes(config.segmentLengthSec)}. Recording itself has no time limit.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Change these in the Settings tab.", style = MaterialTheme.typography.bodySmall)
            }
        }

        // Storage
        val used = segments.sumOf { it.sizeBytes }
        val cap = config.capBytes
        val fraction = if (cap > 0L) (used.toFloat() / cap.toFloat()).coerceIn(0f, 1f) else 0f
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Storage", style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${formatBytes(used)} of ${formatBytes(cap)} used, " +
                        if (segments.size == 1) "1 clip" else "${segments.size} clips",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "When it is full, the oldest clips are replaced. Clips you keep are never replaced.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (!ignoringBattery) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("One more step for screen-off recording", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "With the screen off, your phone may stop the recording to save battery. " +
                            "Allow this app to run without battery restrictions to keep recording reliable.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = {
                        try {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:" + context.packageName),
                                ),
                            )
                        } catch (e: Exception) {
                            try {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            } catch (e2: Exception) {
                                message = "Could not open battery settings."
                            }
                        }
                    }) {
                        Text("Allow")
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeCard(title: String, description: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (selected) "$title  (selected)" else title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
