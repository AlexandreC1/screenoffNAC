package dev.screenoff.recorder.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
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
import dev.screenoff.recorder.remote.LiveHub
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * CameraX wrapper. Nothing is drawn on screen, so it works with the screen off.
 * All methods must be called on the main thread.
 *
 * Normal path: Preview + VideoCapture. The preview stream goes to [FrameTap],
 * which feeds the live view and motion detection, also while recording.
 * Fallback for cameras where that delivers no frames: one use case at a time,
 * image analysis while waiting and video while recording (no live view).
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

    data class MotionStats(val frames: Int, val changed: Int, val needed: Int, val split: Boolean)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val prefs = context.getSharedPreferences("camera", Context.MODE_PRIVATE)
    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var selector: CameraSelector? = null
    private var analysis: ImageAnalysis? = null
    private var facing: CameraFacing? = null
    private var tap: FrameTap? = null
    private var onMotion: () -> Unit = {}
    private var videoBound = false

    @Volatile private var detector = MotionDetector(50)
    private val resetDetector = AtomicBoolean(false)
    @Volatile private var detectMotion = false

    private val framesSeen = AtomicInteger(0)
    @Volatile private var lastChanged = 0
    @Volatile private var needed = 0
    @Volatile var lastFrameAtMs = 0L
        private set
    var openedAtMs = 0L
        private set

    // Frame tap thread only.
    private var luma: ByteBuffer? = null
    private var bitmap: Bitmap? = null
    private val jpegOut = ByteArrayOutputStream(64 * 1024)

    fun motionStats() = MotionStats(framesSeen.get(), lastChanged, needed, splitMode)

    /** True on the fallback path: one use case bound at a time. */
    var splitMode = false
        private set

    /** True on the normal path, while frames come from the preview stream. */
    val usingTap: Boolean get() = tap != null && !splitMode

    val isRecording: Boolean get() = recording != null

    suspend fun open(config: RecorderConfig, mode: RecMode, onMotion: () -> Unit) {
        val p = ProcessCameraProvider.awaitInstance(context)
        provider = p
        this.onMotion = onMotion

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
        detector = MotionDetector(config.motionSensitivity)
        needed = detector.minChangedCells
        detectMotion = mode == RecMode.MOTION
        analysis = buildAnalysis()
        splitMode = false
        videoBound = false
        framesSeen.set(0)
        lastChanged = 0
        lastFrameAtMs = 0L
        openedAtMs = SystemClock.elapsedRealtime()
        p.unbindAll()

        if (!prefs.getBoolean(KEY_TAP_FAILED + config.cameraFacing.name, false)) {
            try {
                val t = tap ?: FrameTap { rgba, w, h -> onTapFrame(rgba, w, h) }.also { tap = it }
                val preview = Preview.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(
                                ResolutionStrategy(Size(640, 360), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                            )
                            .build()
                    )
                    .build()
                preview.setSurfaceProvider(t)
                p.bindToLifecycle(owner, selector, preview, capture)
                videoBound = true
                Diag.log("camera bound: preview tap + video, quality ${config.quality}, audio ${config.audioEnabled}, mode $mode")
                return
            } catch (e: Exception) {
                Diag.log("camera refused preview + video, using one-at-a-time mode", e)
                p.unbindAll()
            }
        } else {
            Diag.log("this camera is known not to deliver preview frames, using one-at-a-time mode")
        }
        bindSplit(p, mode)
    }

    private fun bindSplit(p: ProcessCameraProvider, mode: RecMode) {
        splitMode = true
        if (mode == RecMode.CONTINUOUS) {
            p.bindToLifecycle(owner, requireNotNull(selector), requireNotNull(videoCapture))
            videoBound = true
        } else {
            p.bindToLifecycle(owner, requireNotNull(selector), requireNotNull(analysis))
            videoBound = false
        }
        Diag.log("camera bound one-at-a-time: ${if (videoBound) "video" else "analysis"}")
    }

    /**
     * The preview tap delivered nothing. Remember that for this camera and switch to
     * the one-at-a-time fallback. Only call when not recording.
     */
    fun fallbackFromTap(mode: RecMode): Boolean {
        if (splitMode || recording != null) return false
        val p = provider ?: return false
        facing?.let { prefs.edit().putBoolean(KEY_TAP_FAILED + it.name, true).apply() }
        p.unbindAll()
        resetDetector.set(true)
        openedAtMs = SystemClock.elapsedRealtime()
        bindSplit(p, mode)
        return true
    }

    /** The recorder switched mode. Turns motion detection on or off. */
    fun setMode(mode: RecMode) {
        val detect = mode == RecMode.MOTION
        if (detect && !detectMotion) resetDetector.set(true)
        detectMotion = detect
    }

    /** Fallback path only: hand the camera to the video recorder. */
    fun prepareForRecording() {
        if (!splitMode || videoBound) return
        val p = provider ?: return
        p.unbindAll()
        p.bindToLifecycle(owner, requireNotNull(selector), requireNotNull(videoCapture))
        videoBound = true
    }

    /** Fallback path only: hand the camera back to motion analysis. */
    fun rearm() {
        if (!splitMode || !videoBound) return
        val p = provider ?: return
        resetDetector.set(true)
        p.unbindAll()
        p.bindToLifecycle(owner, requireNotNull(selector), requireNotNull(analysis))
        videoBound = false
    }

    /** Frame tap thread. */
    private fun onTapFrame(rgba: ByteBuffer, w: Int, h: Int) {
        val now = SystemClock.elapsedRealtime()
        lastFrameAtMs = now
        if (framesSeen.incrementAndGet() == 1) Diag.log("first preview frame ${w}x$h")

        if (detectMotion) {
            val y = luma?.takeIf { it.capacity() == w * h } ?: ByteBuffer.allocateDirect(w * h).also { luma = it }
            for (i in 0 until w * h) {
                val r = rgba.get(i * 4).toInt() and 0xFF
                val g = rgba.get(i * 4 + 1).toInt() and 0xFF
                val b = rgba.get(i * 4 + 2).toInt() and 0xFF
                y.put(i, ((r * 77 + g * 150 + b * 29) shr 8).toByte())
            }
            if (resetDetector.getAndSet(false)) detector.reset()
            val motion = detector.analyze(y, w, h, w)
            lastChanged = detector.lastChangedCells
            if (motion) mainHandler.post(onMotion)
        }

        if (LiveHub.wanted) {
            val bmp = bitmap?.takeIf { it.width == w && it.height == h }
                ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap = it }
            rgba.rewind()
            bmp.copyPixelsFromBuffer(rgba)
            jpegOut.reset()
            bmp.compress(Bitmap.CompressFormat.JPEG, 70, jpegOut)
            LiveHub.publish(jpegOut.toByteArray(), tap?.rotationDegrees ?: 0)
        }

        tap?.intervalMs = when {
            LiveHub.viewers.get() > 0 -> 100L
            detectMotion -> 200L
            else -> 500L
        }
    }

    private fun buildAnalysis(): ImageAnalysis {
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
                    lastFrameAtMs = now
                    if (framesSeen.incrementAndGet() == 1) {
                        Diag.log("first analysis image ${image.width}x${image.height}, rowStride ${image.planes[0].rowStride}")
                    }
                    if (detectMotion) {
                        if (resetDetector.getAndSet(false)) detector.reset()
                        val plane = image.planes[0]
                        val motion = detector.analyze(plane.buffer, image.width, image.height, plane.rowStride)
                        lastChanged = detector.lastChangedCells
                        if (motion) mainHandler.post(onMotion)
                    }
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
        videoBound = false
        LiveHub.clear()
    }

    fun shutdown() {
        close()
        tap?.release()
        tap = null
        analysisExecutor.shutdown()
    }

    private companion object {
        const val ANALYSIS_INTERVAL_MS = 200L
        const val KEY_TAP_FAILED = "tap_failed_"
    }
}
