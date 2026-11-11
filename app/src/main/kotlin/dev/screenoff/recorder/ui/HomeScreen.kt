package dev.screenoff.recorder.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = when (status.state) {
                RecState.IDLE -> "Idle"
                RecState.ARMED -> "Armed, waiting for motion"
                RecState.RECORDING -> "Recording"
                RecState.FINALIZING -> "Saving"
            },
            style = MaterialTheme.typography.displaySmall,
        )

        val startedAt = status.segmentStartedAtMs
        if (status.state == RecState.RECORDING && startedAt != null) {
            Text(
                text = formatDuration(now - startedAt),
                style = MaterialTheme.typography.headlineMedium,
            )
        }

        if (status.mode == RecMode.MOTION && (status.state == RecState.ARMED || status.state == RecState.RECORDING)) {
            Text(
                text = if (status.motionFrames == 0) {
                    "Waiting for camera images"
                } else {
                    "Movement ${status.motionChanged} of ${status.motionNeeded} needed. Camera images: ${status.motionFrames}"
                },
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "Wave a hand in front of the camera: the first number should jump above the second.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        val error = status.lastError
        if (error != null) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        Text("Mode", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = config.mode == RecMode.CONTINUOUS,
                onClick = { Graph.config.update { it.copy(mode = RecMode.CONTINUOUS) } },
                label = { Text("Continuous") },
                enabled = !running,
            )
            FilterChip(
                selected = config.mode == RecMode.MOTION,
                onClick = { Graph.config.update { it.copy(mode = RecMode.MOTION) } },
                label = { Text("Motion") },
                enabled = !running,
            )
        }

        if (running) {
            Button(
                onClick = { Triggers.stop(context) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text("Stop", style = MaterialTheme.typography.titleLarge)
            }
        } else {
            Button(
                onClick = {
                    message = null
                    val missing = Permissions.missing(context, config)
                    if (missing.isEmpty()) {
                        doStart()
                    } else {
                        permissionLauncher.launch(missing.toTypedArray())
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
            ) {
                Text("Start", style = MaterialTheme.typography.titleLarge)
            }
        }

        val msg = message
        if (msg != null) {
            Text(
                text = msg,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (status.state == RecState.RECORDING) {
            OutlinedButton(
                onClick = { Triggers.lockCurrent(context) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Keep this clip")
            }
        }

        val used = segments.sumOf { it.sizeBytes }
        val cap = config.capBytes
        val fraction = if (cap > 0L) (used.toFloat() / cap.toFloat()).coerceIn(0f, 1f) else 0f
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Storage", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "${formatBytes(used)} of ${formatBytes(cap)} used",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = if (segments.size == 1) "1 clip" else "${segments.size} clips",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (!ignoringBattery) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Battery optimization", style = MaterialTheme.typography.titleMedium)
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
