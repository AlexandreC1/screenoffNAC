package dev.screenoff.recorder.remote

import java.util.concurrent.atomic.AtomicInteger

/**
 * Hands the newest live JPEG from the camera thread to any number of web
 * viewers. Frames are only encoded while someone is watching.
 */
object LiveHub {
    private val lock = Object()
    private var jpeg: ByteArray? = null
    private var frameNo = 0L

    /** Open live streams. The camera encodes JPEGs only while this is above zero. */
    val viewers = AtomicInteger(0)

    /** Set by a snapshot request so one frame gets encoded even with no stream open. */
    @Volatile var snapshotWanted = false

    @Volatile var rotationDegrees = 0

    val wanted: Boolean get() = viewers.get() > 0 || snapshotWanted

    fun publish(bytes: ByteArray, rotation: Int) {
        synchronized(lock) {
            jpeg = bytes
            frameNo++
            rotationDegrees = rotation
            snapshotWanted = false
            lock.notifyAll()
        }
    }

    /** Waits for a frame newer than [after]. Returns null on timeout. */
    fun await(after: Long, timeoutMs: Long): Pair<Long, ByteArray>? {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (frameNo <= after || jpeg == null) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                lock.wait(left)
            }
            return frameNo to jpeg!!
        }
    }

    fun clear() {
        synchronized(lock) {
            jpeg = null
            lock.notifyAll()
        }
    }
}

/** Commands from the web page to the running recorder. Set by the service while it runs. */
object RemoteBridge {
    sealed interface Command {
        data class Mode(val mode: dev.screenoff.core.RecMode) : Command
        data object Keep : Command
    }

    @Volatile var sink: ((Command) -> Unit)? = null

    fun send(c: Command): Boolean {
        val s = sink ?: return false
        s(c)
        return true
    }
}
