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
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
        videoCapture = capture
        splitMode = false
        analysis = null
        p.unbindAll()
        if (mode == RecMode.MOTION) {
            val a = buildAnalysis(config, onMotion)
            analysis = a
            try {
                p.bindToLifecycle(owner, selector, capture, a)
            } catch (e: Exception) {
                // Common on entry-level cameras. Watch first, then switch to video on motion.
                p.unbindAll()
                p.bindToLifecycle(owner, selector, a)
                splitMode = true
            }
        } else {
            p.bindToLifecycle(owner, selector, capture)
        }
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
                    if (detector.analyze(plane.buffer, image.width, image.height, plane.rowStride)) {
                        mainHandler.post(onMotion)
                    }
                }
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
    }
}
