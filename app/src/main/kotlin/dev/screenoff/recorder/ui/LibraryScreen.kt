package dev.screenoff.recorder.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.screenoff.core.RecMode
import dev.screenoff.core.Segment
import dev.screenoff.recorder.Graph
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val segments by Graph.index.segments.collectAsStateWithLifecycle()
    var pendingDelete by remember { mutableStateOf<Segment?>(null) }

    if (segments.isEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "No clips yet. Recorded clips will show up here.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(segments, key = { it.id }) { seg ->
                SegmentRow(
                    seg = seg,
                    onOpen = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW)
                                .setDataAndType(Graph.storage.viewUri(seg.uri), "video/mp4")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "No app found to play this clip.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onToggleLock = { Graph.index.setLocked(seg.id, !seg.locked) },
                    onDelete = { pendingDelete = seg },
                )
            }
        }
    }

    val target = pendingDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this clip?") },
            text = { Text("The video file will be removed. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    // Re-check against the live index: the clip may have been locked meanwhile.
                    val live = Graph.index.snapshot().firstOrNull { it.id == target.id }
                    if (live != null && !live.locked) {
                        val seg = live
                        Graph.io.launch {
                            Graph.storage.delete(seg.uri)
                            Graph.index.remove(seg.id)
                        }
                    }
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun SegmentRow(
    seg: Segment,
    onOpen: () -> Unit,
    onToggleLock: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onOpen)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatDateTime(seg.startedAtMs),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (seg.locked) {
                    Text(
                        text = "Locked",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            val modeLabel = if (seg.mode == RecMode.MOTION) "Motion" else "Continuous"
            Text(
                text = "${formatDuration(seg.durationMs)} · ${formatBytes(seg.sizeBytes)} · $modeLabel",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onToggleLock) {
                    Text(if (seg.locked) "Unlock" else "Lock")
                }
                TextButton(onClick = onDelete, enabled = !seg.locked) {
                    Text("Delete")
                }
            }
        }
    }
}
