package dev.screenoff.recorder.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.screenoff.core.CameraFacing
import dev.screenoff.core.VideoQuality
import androidx.core.content.FileProvider
import dev.screenoff.recorder.Diag
import dev.screenoff.recorder.Graph
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val config by Graph.config.config.collectAsStateWithLifecycle()

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: Exception) {
                // Keep going; recording falls back to the app folder if the folder is unreachable.
            }
            Graph.config.update { it.copy(folderTreeUri = uri.toString()) }
        }
    }

    val treeUri = config.folderTreeUri
    val folderName by produceState<String?>(initialValue = null, treeUri) {
        value = if (treeUri == null) null else withContext(Dispatchers.IO) { Graph.storage.folderLabel(treeUri) }
    }

    var sensitivity by remember { mutableFloatStateOf(config.motionSensitivity.toFloat()) }
    LaunchedEffect(config.motionSensitivity) {
        sensitivity = config.motionSensitivity.toFloat()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Section("Camera") {
            ChoiceRow(
                options = listOf("Back" to CameraFacing.BACK, "Front" to CameraFacing.FRONT),
                selected = config.cameraFacing,
                onSelect = { v -> Graph.config.update { it.copy(cameraFacing = v) } },
            )
        }

        Section("Video quality") {
            ChoiceRow(
                options = VideoQuality.entries.map { q ->
                    "${q.name} (${q.bitrateBps / 1_000_000} Mbps)" to q
                },
                selected = config.quality,
                onSelect = { v -> Graph.config.update { it.copy(quality = v) } },
            )
        }

        Section("Sound") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Record sound",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = config.audioEnabled,
                    onCheckedChange = { v -> Graph.config.update { it.copy(audioEnabled = v) } },
                )
            }
            Text(
                "Recording other people's voices may require their consent where you live.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Section("Length of each file") {
            Text(
                "Recording never stops by itself. This only sets how long each video file is before a new one starts. " +
                    "Pick a length, type any number of minutes, or choose No limit for one single file until you press Stop.",
                style = MaterialTheme.typography.bodySmall,
            )
            ChoiceRow(
                options = listOf("No limit" to 0) + listOf(1, 3, 5, 10, 30, 60, 120).map { formatMinutes(it * 60) to it * 60 },
                selected = config.segmentLengthSec,
                onSelect = { v -> Graph.config.update { it.copy(segmentLengthSec = v) } },
            )
            NumberField(
                label = "Minutes per file",
                current = config.segmentLengthSec / 60,
                range = 1..10080,
                onValid = { v -> Graph.config.update { it.copy(segmentLengthSec = v * 60) } },
            )
            val audioBps = if (config.audioEnabled) 128_000 else 0
            val bytesPerSec = (config.quality.bitrateBps + audioBps).toLong() / 8L
            val fileBytes = bytesPerSec * config.planningSegmentSec
            Text(
                (if (config.segmentLengthSec <= 0) "With no limit, one hour is about ${formatBytes(bytesPerSec * 3600)}."
                else "One file will be about ${formatBytes(fileBytes)}.") + " If the phone restarts or the app is closed by the " +
                    "system during a recording, the file in progress is lost, so very long files are riskier.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (config.segmentLengthSec <= 0) {
                Text(
                    "With no limit, the storage limit is only applied before a recording starts. The recording itself continues until you stop it or the phone runs out of space.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (fileBytes > config.capBytes) {
                Text(
                    "This is larger than your storage limit below. Raise the limit, or older clips will all be removed to make room.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Section("Storage limit") {
            Text(
                "How much space recordings may use. When it is full, the oldest clips are replaced.",
                style = MaterialTheme.typography.bodySmall,
            )
            ChoiceRow(
                options = listOf(1, 2, 4, 8, 16, 32, 64).map { "$it GB" to (it.toLong() shl 30) },
                selected = config.capBytes,
                onSelect = { v -> Graph.config.update { it.copy(capBytes = v) } },
            )
            NumberField(
                label = "GB",
                current = (config.capBytes shr 30).toInt(),
                range = 1..512,
                onValid = { v -> Graph.config.update { it.copy(capBytes = v.toLong() shl 30) } },
            )
        }

        Section("Where clips are saved") {
            Text(
                text = if (treeUri == null) "App folder (default)" else (folderName ?: "Selected folder"),
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { folderLauncher.launch(null) }) {
                    Text("Choose folder")
                }
                OutlinedButton(
                    onClick = { Graph.config.update { it.copy(folderTreeUri = null) } },
                    enabled = treeUri != null,
                ) {
                    Text("Use app folder")
                }
            }
        }

        Section("Movement sensitivity: ${sensitivity.roundToInt()}") {
            Text(
                "Higher reacts to smaller movement. Raise it if the security mode misses things, lower it if it records too often.",
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = sensitivity,
                onValueChange = { sensitivity = it },
                valueRange = 0f..100f,
                onValueChangeFinished = {
                    val v = sensitivity.roundToInt()
                    Graph.config.update { it.copy(motionSensitivity = v) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Section("Keep recording after movement stops") {
            ChoiceRow(
                options = listOf(5, 10, 20, 30, 60).map { "$it s" to it },
                selected = config.postRollSec,
                onSelect = { v -> Graph.config.update { it.copy(postRollSec = v) } },
            )
        }

        Section("Stop when battery below") {
            ChoiceRow(
                options = listOf(5, 10, 15, 20).map { "$it%" to it },
                selected = config.lowBatteryStopPercent,
                onSelect = { v -> Graph.config.update { it.copy(lowBatteryStopPercent = v) } },
            )
        }

        Section("Diagnostics") {
            Text(
                "If something does not work, share this log with whoever fixes the app. It contains no video or sound.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { shareDiagnostics(context) }) { Text("Share diagnostics") }
        }

        Text(
            "Changes apply the next time recording starts.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun shareDiagnostics(context: Context) {
    val file = Diag.file(context)
    if (!file.exists()) {
        Toast.makeText(context, "No diagnostics yet", Toast.LENGTH_SHORT).show()
        return
    }
    try {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "ScreenOff Recorder diagnostics")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(send, "Share diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        Toast.makeText(context, "Could not share: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

/** Free numeric entry. Applies as soon as the typed value is inside [range]. */
@Composable
private fun NumberField(label: String, current: Int, range: IntRange, onValid: (Int) -> Unit) {
    var text by remember(current) { mutableStateOf(if (current <= 0) "" else current.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            text = typed.filter { it.isDigit() }.take(4)
            val v = text.toIntOrNull()
            if (v != null && v in range && v != current) onValid(v)
        },
        label = { Text(label) },
        singleLine = true,
        isError = text.toIntOrNull()?.let { it !in range } ?: false,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun <T> ChoiceRow(
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (label, value) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}
