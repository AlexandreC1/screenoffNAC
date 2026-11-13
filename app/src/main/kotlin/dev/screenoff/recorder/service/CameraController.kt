package dev.screenoff.recorder.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dev.screenoff.core.CameraFacing
import dev.screenoff.core.MotionDetector
import dev.screenoff.core.RecMode
import dev.screenoff.core.RecorderConfig
import dev.screenoff.core.VideoQuality
import dev.screenoff.recorder.Diag
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * CameraX wrapper. There is no Preview use case: nothing is drawn, so it works
 * with the screen off. All methods must be called on the main thread.
 */
class CameraController(private val context: Context, private val owner: LifecycleOwner) {

    data class FinalizeResult(
        /** The file is a playable video worth keeping. */
        val usable: Boolean,
        val durationMs: Long,
        val sizeBytes: Long,
        /** Not null when the recorder stopped because of an error. */
        val error: String?,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var selector: CameraSelector? = null
    private var analysis: ImageAnalysis? = null
    private val resetDetector = AtomicBoolean(false)
    private val prefs = context.getSharedPreferences("camera", Context.MODE_PRIVATE)
    private var facing: CameraFacing? = null

    data class MotionStats(val frames: Int, val changed: Int, val needed: Int, val split: Boolean)

    private val framesSeen = AtomicInteger(0)
    @Volatile private var lastChanged = 0
    @Volatile private var needed = 0
    @Volatile var lastFrameAtMs = 0L
        private set
    var openedAtMs = 0L
        private set

    fun motionStats() = MotionStats(framesSeen.get(), lastChanged, needed, splitMode)

    /**
     * True when this camera cannot run video and analysis at the same time.
     * Then analysis runs alone while armed and video runs alone while recording.
     */
    var splitMode = false
        private set

    val isRecording: Boolean get() = recording != null

    suspend fun open(config: RecorderConfig, mode: RecMode, onMotion: () -> Unit) {
        val p = ProcessCameraProvider.awaitInstance(context)
        provider = p

        val selector = when (config.cameraFacing) {
            CameraFacing.BACK -> CameraSelector.DEFAULT_BACK_CAMERA
            CameraFacing.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
        }
        val quality = when (config.quality) {
            VideoQuality.SD -> Quality.SD
            VideoQuality.HD -> Quality.HD
            VideoQuality.FHD -> Quality.FHD
        }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality)))
            .setTargetVideoEncodingBitRate(config.quality.bitrateBps)
            .build()
        val capture = VideoCapture.withOutput(recorder)

        this.selector = selector
        facing = config.cameraFacing
        videoCapture = capture
        splitMode = false
        analysis = null
        framesSeen.set(0)
        lastChanged = 0
        lastFrameAtMs = 0L
        openedAtMs = SystemClock.elapsedRealtime()
        p.unbindAll()
        if (mode == RecMode.MOTION) {
            val a = buildAnalysis(config, onMotion)
            analysis = a
            try {
                // Some phones accept this combination and then fail to start it. Once seen, skip it.
                if (prefs.getBoolean(KEY_SPLIT + config.cameraFacing.name, false)) {
                    throw IllegalStateException("known not to work on this camera")
                }
                p.bindToLifecycle(owner, selector, capture, a)
                Diag.log("camera bound: video + analysis together, sensitivity ${config.motionSensitivity}")
            } catch (e: Exception) {
                // Common on entry-level cameras. Watch first, then switch to video on motion.
                Diag.log("camera cannot run video + analysis together, using split mode", e)
                p.unbindAll()
                p.bindToLifecycle(owner, selector, a)
                splitMode = true
            }
        } else {
            p.bindToLifecycle(owner, selector, capture)
            Diag.log("camera bound: video, quality ${config.quality}, audio ${config.audioEnabled}")
        }
    }

    /**
     * Some phones accept video + analysis together but then never deliver analysis
     * images. The service calls this when that happens, while armed.
     */
    fun fallbackToSplit(): Boolean {
        if (splitMode || recording != null) return false
        val p = provider ?: return false
        p.unbindAll()
        p.bindToLifecycle(owner, requireNotNull(selector), requireNotNull(analysis))
        splitMode = true
        resetDetector.set(true)
        openedAtMs = SystemClock.elapsedRealtime()
        facing?.let { prefs.edit().putBoolean(KEY_SPLIT + it.name, true).apply() }
        return true
    }

    /** Split mode only: hand the camera to the video recorder. */
    fun prepareForRecording() {
        if (!splitMode) return
        val p = provider ?: return
        p.unbindAll()
        p.bindToLifecycle(owner, requireNotNull(selector), requireNotNull(videoCapture))
    }

    /** Split mode only: hand the camera back to motion analysis. */
    fun rearm() {
        if (!splitMode) return
        val p = provider ?: return
        resetDetector.set(true)
        p.unbindAll()
        p.bindToLifecycle(owner, requireNotNull(selector), requireNotNull(analysis))
    }

    private fun buildAnalysis(config: RecorderConfig, onMotion: () -> Unit): ImageAnalysis {
        val detector = MotionDetector(config.motionSensitivity)
        needed = detector.minChangedCells
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(320, 240), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    )
                    .build()
            )
            .build()
        var lastAnalyzedAt = 0L
        analysis.setAnalyzer(analysisExecutor) { image ->
            try {
                val now = SystemClock.elapsedRealtime()
                if (now - lastAnalyzedAt >= ANALYSIS_INTERVAL_MS) {
                    lastAnalyzedAt = now
                    if (resetDetector.getAndSet(false)) detector.reset()
                    val plane = image.planes[0]
                    val motion = detector.analyze(plane.buffer, image.width, image.height, plane.rowStride)
                    lastChanged = detector.lastChangedCells
                    lastFrameAtMs = now
                    if (framesSeen.incrementAndGet() == 1) {
                        Diag.log("first analysis image ${image.width}x${image.height}, rowStride ${plane.rowStride}, format ${image.format}")
                    }
                    if (motion) mainHandler.post(onMotion)
                }
            } catch (t: Throwable) {
                Diag.log("analysis failed", t)
            } finally {
                image.close()
            }
        }
        return analysis
    }

    /** @return false when recording could not be started */
    @SuppressLint("MissingPermission")
    fun startRecording(file: File, audio: Boolean, onFinalized: (FinalizeResult) -> Unit): Boolean {
        val capture = videoCapture ?: return false
        if (recording != null) return false
        return try {
            var pending = capture.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
            val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            if (audio && micGranted) pending = pending.withAudioEnabled()
            recording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    recording = null
                    val stats = event.recordingStats
                    val size = maxOf(stats.numBytesRecorded, file.length())
                    val hasError = event.hasError()
                    Diag.log(
                        "clip finalized: ${stats.recordedDurationNanos / 1_000_000} ms, $size bytes, " +
                            "audio ${stats.audioStats.audioState}, error ${event.error}"
                    )
                    onFinalized(
                        FinalizeResult(
                            usable = size > 0 && event.error != VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA,
                            durationMs = stats.recordedDurationNanos / 1_000_000,
                            sizeBytes = size,
                            error = if (hasError) "Recorder error ${event.error}" else null,
                        )
                    )
                }
            }
            true
        } catch (e: Exception) {
            Diag.log("recording could not start", e)
            recording = null
            false
        }
    }

    /** Asks the recorder to close the file. The finalize callback follows. */
    fun stopRecording() {
        recording?.stop()
    }

    fun close() {
        recording?.stop()
        runCatching { provider?.unbindAll() }
        videoCapture = null
        analysis = null
    }

    fun shutdown() {
        close()
        analysisExecutor.shutdown()
    }

    private companion object {
        const val ANALYSIS_INTERVAL_MS = 200L
        const val KEY_SPLIT = "split_"
    }
}
