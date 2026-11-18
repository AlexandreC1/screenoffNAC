package dev.screenoff.recorder.remote

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import dev.screenoff.recorder.Diag

/**
 * Live sound for the web page. The microphone is opened only while at least one
 * listener is connected. Audio is 16 kHz mono, G.711 mu-law (8 bits per sample,
 * 16 KB per second), cut into 20 ms chunks that every listener receives in order.
 */
object AudioHub {
    const val SAMPLE_RATE = 16_000
    private const val CHUNK_SAMPLES = SAMPLE_RATE / 50
    private const val RING = 100 // 2 seconds of chunks

    private val lock = Object()
    private val ring = arrayOfNulls<ByteArray>(RING)
    private var seq = 0L
    private var listeners = 0
    private var thread: Thread? = null

    /** True when Android delivers silence because another recording has the microphone. */
    @Volatile var silenced = false
        private set

    /** True while the microphone is open for live listening. */
    @Volatile var capturing = false
        private set

    /** Registers a listener and returns the sequence number to read after. */
    fun join(): Long = synchronized(lock) {
        listeners++
        if (thread == null) {
            thread = Thread({ captureLoop() }, "live-audio").apply { isDaemon = true; start() }
        }
        seq
    }

    fun leave() = synchronized(lock) {
        listeners = (listeners - 1).coerceAtLeast(0)
        lock.notifyAll()
    }

    /**
     * Waits for chunks newer than [after]. Returns the new last sequence number and the
     * concatenated bytes, or null on timeout. A listener that fell behind skips ahead.
     */
    fun await(after: Long, timeoutMs: Long): Pair<Long, ByteArray>? {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (seq <= after) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                lock.wait(left)
            }
            val from = maxOf(after + 1, seq - RING + 1)
            val out = java.io.ByteArrayOutputStream(((seq - from + 1) * CHUNK_SAMPLES).toInt())
            for (s in from..seq) ring[(s % RING).toInt()]?.let { out.write(it) }
            return seq to out.toByteArray()
        }
    }

    @SuppressLint("MissingPermission") // Checked by the caller: the page only offers sound when it is allowed.
    private fun captureLoop() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        var record: AudioRecord? = null
        var failed = false
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, CHUNK_SAMPLES * 2 * 8),
            )
            check(record.state == AudioRecord.STATE_INITIALIZED) { "microphone not available" }
            record.startRecording()
            capturing = true
            Diag.log("live sound started")
            val pcm = ShortArray(CHUNK_SAMPLES)
            var chunks = 0
            while (true) {
                synchronized(lock) { if (listeners == 0) return }
                var filled = 0
                while (filled < CHUNK_SAMPLES) {
                    val n = record.read(pcm, filled, CHUNK_SAMPLES - filled)
                    if (n <= 0) throw IllegalStateException("microphone read returned $n")
                    filled += n
                }
                val chunk = ByteArray(CHUNK_SAMPLES)
                for (i in 0 until CHUNK_SAMPLES) chunk[i] = muLaw(pcm[i].toInt())
                synchronized(lock) {
                    seq++
                    ring[(seq % RING).toInt()] = chunk
                    lock.notifyAll()
                }
                if (++chunks % 50 == 0 && Build.VERSION.SDK_INT >= 29) {
                    val now = record.activeRecordingConfiguration?.isClientSilenced == true
                    if (now != silenced) Diag.log("live sound ${if (now) "muted by Android (microphone in use)" else "unmuted"}")
                    silenced = now
                }
            }
        } catch (t: Throwable) {
            failed = true
            Diag.log("live sound stopped", t)
        } finally {
            runCatching { record?.stop() }
            runCatching { record?.release() }
            capturing = false
            silenced = false
            synchronized(lock) {
                thread = null
                lock.notifyAll()
                // A listener may have joined while this thread was ending.
                if (!failed && listeners > 0) thread = Thread({ captureLoop() }, "live-audio").apply { isDaemon = true; start() }
            }
            Diag.log("live sound closed")
        }
    }

    /** G.711 mu-law encoding of one 16-bit sample. */
    private fun muLaw(sample: Int): Byte {
        var s = sample
        val sign = if (s < 0) 0x80 else 0
        if (s < 0) s = -s
        if (s > 32635) s = 32635
        s += 0x84
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && (s and mask) == 0) {
            exponent--
            mask = mask shr 1
        }
        val mantissa = (s shr (exponent + 3)) and 0x0F
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }
}
