package dev.screenoff.recorder

import android.app.Application
import dev.screenoff.core.RecState
import dev.screenoff.core.RecorderStatus
import dev.screenoff.recorder.data.ConfigStore
import dev.screenoff.recorder.data.SegmentIndex
import dev.screenoff.recorder.data.StorageGateway
import dev.screenoff.recorder.service.Notifier
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Diag.init(this)
        Graph.init(this)
        Notifier.createChannels(this)
        // Leftovers from a crash: drop index rows whose file is gone, and unplayable staging files.
        Graph.io.launch {
            if (RecorderBus.status.value.state == RecState.IDLE) {
                Graph.storage.clearStaging()
                Graph.index.reconcile { Graph.storage.exists(it) }
            }
        }
    }
}

/** Process-wide singletons. Everything the UI, the service and the triggers share. */
object Graph {
    lateinit var config: ConfigStore
        private set
    lateinit var index: SegmentIndex
        private set
    lateinit var storage: StorageGateway
        private set

    /** One thread, so file work is serialized. */
    val ioDispatcher: CoroutineDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    /** Outlives the service scope, so a clip being moved is not cut off when recording stops. */
    val io = CoroutineScope(SupervisorJob() + ioDispatcher)

    fun init(app: Application) {
        config = ConfigStore(app)
        index = SegmentIndex(app)
        storage = StorageGateway(app)
    }
}

/** Live recorder state. IDLE whenever the service is not running. */
object RecorderBus {
    private val _status = MutableStateFlow(RecorderStatus())
    val status: StateFlow<RecorderStatus> = _status

    val isRunning: Boolean get() = _status.value.state != RecState.IDLE

    internal fun publish(status: RecorderStatus) {
        _status.value = status
    }
}

object Actions {
    const val START_CONTINUOUS = "dev.screenoff.recorder.action.START_CONTINUOUS"
    const val START_MOTION = "dev.screenoff.recorder.action.START_MOTION"
    const val STOP = "dev.screenoff.recorder.action.STOP"
    const val TOGGLE = "dev.screenoff.recorder.action.TOGGLE"
    const val LOCK_CURRENT = "dev.screenoff.recorder.action.LOCK_CURRENT"

    /** Service-internal start, carries [EXTRA_MODE]. */
    const val SERVICE_START = "dev.screenoff.recorder.action.SERVICE_START"
    const val EXTRA_MODE = "mode"
}
