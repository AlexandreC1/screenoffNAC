package dev.screenoff.recorder.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.screenoff.core.CameraFacing
import dev.screenoff.core.VideoQuality
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

        Section("Quality") {
            ChoiceRow(
                options = VideoQuality.entries.map { q ->
                    "${q.name} (${q.bitrateBps / 1_000_000} Mbps)" to q
                },
                selected = config.quality,
                onSelect = { v -> Graph.config.update { it.copy(quality = v) } },
            )
        }

        Section("Audio") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Record audio",
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

        Section("Segment length") {
            ChoiceRow(
                options = listOf(1, 3, 5, 10).map { "$it min" to it * 60 },
                selected = config.segmentLengthSec,
                onSelect = { v -> Graph.config.update { it.copy(segmentLengthSec = v) } },
            )
        }

        Section("Storage cap") {
            ChoiceRow(
                options = listOf(1, 2, 4, 8, 16, 32).map { "$it GB" to (it.toLong() shl 30) },
                selected = config.capBytes,
                onSelect = { v -> Graph.config.update { it.copy(capBytes = v) } },
            )
        }

        Section("Folder") {
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

        Section("Motion sensitivity: ${sensitivity.roundToInt()}") {
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

        Section("Keep recording after motion stops") {
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

        Text(
            "Changes apply the next time recording starts.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
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
