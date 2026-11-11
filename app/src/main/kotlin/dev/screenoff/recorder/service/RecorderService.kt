package dev.screenoff.recorder.service

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.screenoff.core.EvictionPolicy
import dev.screenoff.core.RecMode
import dev.screenoff.core.RecState
import dev.screenoff.core.RecorderConfig
import dev.screenoff.core.RecorderMachine
import dev.screenoff.core.RecorderMachine.Effect
import dev.screenoff.core.RecorderMachine.Event
import dev.screenoff.core.RecorderStatus
import dev.screenoff.core.Segment
import dev.screenoff.recorder.Actions
import dev.screenoff.recorder.Graph
import dev.screenoff.recorder.RecorderBus
import dev.screenoff.recorder.triggers.Triggers
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that owns the camera. It keeps recording with the screen
 * off because a foreground service of type camera keeps camera access.
 *
 * Every event goes through one channel and is handled on the main thread, one
 * at a time, so the state machine never sees concurrent calls.
 */
class RecorderService : LifecycleService() {

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val machine = RecorderMachine()
    private lateinit var camera: CameraController

    private var config = RecorderConfig()
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false
    /** True from the accepted start request until the service has fully finished. */
    private var active = false
    private var cameraOpen = false
    private var tickCount = 0

    private var segmentStartWallMs: Long? = null
    private var lockCurrent = false
    private val publishJobs = ArrayList<Job>()

    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

    override fun onCreate() {
        super.onCreate()
        camera = CameraController(this, this)
        lifecycleScope.launch { for (e in events) handle(e) }
        lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                events.trySend(Event.Tick(now()))
                if (++tickCount % 15 == 0) checkBattery()
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                if (status >= PowerManager.THERMAL_STATUS_SEVERE) fail("The phone got too hot")
            }
            getSystemService(PowerManager::class.java).addThermalStatusListener(mainExecutor, listener)
            thermalListener = listener
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.i("ScreenOff", "service command ${intent?.action} active=$active state=${machine.state}")
        when (intent?.action) {
            Actions.SERVICE_START -> {
                val starting = !active
                if (starting) config = Graph.config.config.value
                // startForegroundService() was used, so startForeground() must follow every time.
                if (!goForeground()) {
                    if (starting) stopSelf()
                    return START_NOT_STICKY
                }
                if (starting) {
                    active = true
                    val mode = runCatching { RecMode.valueOf(intent?.getStringExtra(Actions.EXTRA_MODE) ?: "") }
                        .getOrDefault(config.mode)
                    events.trySend(
                        Event.Start(mode, now(), config.segmentLengthSec * 1000L, config.postRollSec * 1000L)
                    )
                }
            }
            Actions.STOP -> {
                if (active) events.trySend(Event.Stop(now())) else stopSelf()
            }
            Actions.LOCK_CURRENT -> {
                if (machine.state == RecState.RECORDING) {
                    lockCurrent = true
                    publishStatus()
                }
            }
            else -> if (!active) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun goForeground(): Boolean {
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (config.audioEnabled && micGranted) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        return try {
            ServiceCompat.startForeground(this, Notifier.ONGOING_ID, Notifier.ongoing(this, currentStatus(), lockCurrent), type)
            foreground = true
            true
        } catch (e: Exception) {
            // Typically: started while the app was not visible, or a permission is missing.
            Log.e("ScreenOff", "startForeground refused", e)
            Notifier.error(this, "Android refused to start the camera service: ${e.message ?: e.javaClass.simpleName}")
            false
        }
    }

    private suspend fun handle(event: Event) {
        val effects = machine.on(event)
        for (fx in effects) run(fx)
        if (event is Event.SegmentFinalized && machine.state == RecState.ARMED) {
            try {
                camera.rearm()
            } catch (e: Exception) {
                fail("The camera could not go back to watching: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        if (effects.isNotEmpty() || event !is Event.Tick) publishStatus()
    }

    private suspend fun run(effect: Effect) {
        when (effect) {
            is Effect.OpenCamera -> openCamera(effect.mode)
            Effect.StartSegment -> startSegment()
            Effect.StopSegment -> {
                if (camera.isRecording) camera.stopRecording()
                else events.trySend(Event.SegmentFinalized(now())) // Nothing was being written.
            }
            Effect.CloseCamera -> {
                cameraOpen = false
                camera.close()
            }
            is Effect.Finish -> finish(effect.error)
        }
    }

    private suspend fun openCamera(mode: RecMode) {
        acquireWakeLock()
        try {
            camera.open(config, mode) { events.trySend(Event.Motion(now())) }
            cameraOpen = true
        } catch (t: Throwable) {
            fail("The camera could not be opened: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private suspend fun startSegment() {
        val mode = machine.mode ?: return
        if (!cameraOpen) return // Opening failed and a Fail event is already queued. Do not evict anything.
        val audioBps = if (config.audioEnabled) 128_000 else 0
        val nextBytes = EvictionPolicy.estimateSegmentBytes(config.quality.bitrateBps + audioBps, config.segmentLengthSec)

        // Wait for clips still being moved into the library so the index is complete.
        publishJobs.toList().joinAll()
        publishJobs.clear()

        val fits = withContext(Graph.ioDispatcher) { makeRoom(nextBytes) }
        if (!fits) {
            fail("Storage is full and only kept clips remain")
            return
        }

        val startedWall = System.currentTimeMillis()
        val file = Graph.storage.newStagingFile(mode, startedWall)
        lockCurrent = false
        try {
            camera.prepareForRecording()
        } catch (e: Exception) {
            fail("The camera could not switch to recording: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        val started = camera.startRecording(file, config.audioEnabled) { result ->
            onSegmentClosed(file, mode, startedWall, result)
        }
        if (started) segmentStartWallMs = startedWall else fail("Recording could not start")
    }

    /** Deletes the oldest unlocked clips until the next segment fits. Runs on the file thread. */
    private fun makeRoom(nextBytes: Long): Boolean {
        val plan = EvictionPolicy.plan(
            segments = Graph.index.snapshot(),
            capBytes = config.capBytes,
            nextSegmentBytes = nextBytes,
            deviceFreeBytes = Graph.storage.freeBytes(),
            freeSpaceFloorBytes = config.freeSpaceFloorBytes,
        )
        for (victim in plan.victims) {
            if (victim.locked) continue // The policy never returns these. Checked again on purpose.
            Graph.storage.delete(victim.uri)
            Graph.index.remove(victim.id)
        }
        return plan.satisfied
    }

    /** CameraX finalize callback, on the main thread. */
    private fun onSegmentClosed(file: File, mode: RecMode, startedWall: Long, result: CameraController.FinalizeResult) {
        val locked = lockCurrent
        val folder = config.folderTreeUri
        segmentStartWallMs = null
        lockCurrent = false
        publishJobs += Graph.io.launch {
            if (result.usable) {
                try {
                    val size = file.length()
                    val published = Graph.storage.publish(file, mode, folder)
                    Graph.index.add(Segment(file.name, published.uri, mode, startedWall, result.durationMs, size, locked))
                    if (published.fellBackToAppFolder) {
                        Notifier.error(this@RecorderService, "Your folder was not reachable. The clip was saved in the app folder.")
                    }
                } catch (e: Exception) {
                    Notifier.error(this@RecorderService, "A clip could not be saved: ${e.message ?: e.javaClass.simpleName}")
                }
            } else {
                file.delete()
            }
        }
        events.trySend(Event.SegmentFinalized(now(), failed = result.error != null, reason = result.error))
    }

    private suspend fun finish(error: String?) {
        Log.i("ScreenOff", "finish error=$error")
        segmentStartWallMs = null
        releaseWakeLock()
        publishStatus(error)
        if (error != null) Notifier.error(this, error)
        // Stay alive until every clip is safely in the library.
        publishJobs.toList().joinAll()
        publishJobs.clear()
        if (foreground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        active = false
        stopSelf()
    }

    private fun fail(reason: String) {
        events.trySend(Event.Fail(now(), reason))
    }

    private fun checkBattery() {
        if (machine.state == RecState.IDLE) return
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (level < 0 || scale <= 0 || plugged) return
        if (level * 100 / scale <= config.lowBatteryStopPercent) fail("Battery is low")
    }

    private fun currentStatus(error: String? = machine.error) =
        RecorderStatus(machine.state, machine.mode, segmentStartWallMs, error)

    private fun publishStatus(error: String? = machine.error) {
        val status = currentStatus(error)
        RecorderBus.publish(status)
        if (foreground && status.state != RecState.IDLE) Notifier.updateOngoing(this, status, lockCurrent)
        Triggers.refreshSurfaces(this)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "screenoff:recorder")
            .apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= 29) {
            thermalListener?.let { getSystemService(PowerManager::class.java).removeThermalStatusListener(it) }
        }
        camera.shutdown()
        releaseWakeLock()
        events.close()
        RecorderBus.publish(RecorderStatus(lastError = RecorderBus.status.value.lastError))
        Triggers.refreshSurfaces(this)
        super.onDestroy()
    }

    private fun now(): Long = SystemClock.elapsedRealtime()
}
