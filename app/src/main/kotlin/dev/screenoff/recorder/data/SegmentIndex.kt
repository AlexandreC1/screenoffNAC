package dev.screenoff.recorder.data

import android.content.Context
import dev.screenoff.core.RecMode
import dev.screenoff.core.Segment
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The list of finalized clips, newest first, persisted as one small JSON file.
 * Written atomically (temp file then rename). Mutations are synchronized.
 */
class SegmentIndex(context: Context) {
    private val file = File(context.filesDir, "segments.json")
    private val _segments = MutableStateFlow(load())
    val segments: StateFlow<List<Segment>> = _segments

    fun snapshot(): List<Segment> = _segments.value

    @Synchronized
    fun add(segment: Segment) = commit((_segments.value.filter { it.id != segment.id } + segment))

    @Synchronized
    fun remove(id: String) = commit(_segments.value.filter { it.id != id })

    @Synchronized
    fun setLocked(id: String, locked: Boolean) =
        commit(_segments.value.map { if (it.id == id) it.copy(locked = locked) else it })

    /** Drops rows whose file no longer exists, for example deleted with a file manager. */
    fun reconcile(exists: (uri: String) -> Boolean) {
        val gone = snapshot().filter { !exists(it.uri) }.map { it.id }.toSet()
        if (gone.isEmpty()) return
        synchronized(this) { commit(_segments.value.filter { it.id !in gone }) }
    }

    private fun commit(list: List<Segment>) {
        val sorted = list.sortedByDescending { it.startedAtMs }
        val arr = JSONArray()
        for (s in sorted) {
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("uri", s.uri)
                    .put("mode", s.mode.name)
                    .put("startedAtMs", s.startedAtMs)
                    .put("durationMs", s.durationMs)
                    .put("sizeBytes", s.sizeBytes)
                    .put("locked", s.locked)
            )
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString())
            tmp.delete()
        }
        _segments.value = sorted
    }

    private fun load(): List<Segment> = try {
        if (!file.exists()) emptyList() else {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Segment(
                    id = o.getString("id"),
                    uri = o.getString("uri"),
                    mode = if (o.optString("mode") == RecMode.MOTION.name) RecMode.MOTION else RecMode.CONTINUOUS,
                    startedAtMs = o.getLong("startedAtMs"),
                    durationMs = o.optLong("durationMs"),
                    sizeBytes = o.optLong("sizeBytes"),
                    locked = o.optBoolean("locked"),
                )
            }.sortedByDescending { it.startedAtMs }
        }
    } catch (e: Exception) {
        emptyList()
    }
}
