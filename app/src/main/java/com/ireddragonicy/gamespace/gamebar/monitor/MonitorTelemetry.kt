package com.ireddragonicy.gamespace.gamebar.monitor

import android.content.Context
import com.ireddragonicy.gamespace.gamebar.fps.FpsInteractor
import com.ireddragonicy.gamespace.telemetry.TelemetryBus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import com.ireddragonicy.gamespace.telemetry.TelemetrySnapshot

/**
 * Floating-monitor telemetry. A3: now a *pure projection* of [TelemetryBus].
 *
 * The bus owns every load/temp/power/frequency number and emits one immutable
 * snapshot per second. This class only adds the two things the bus does not
 * produce for the monitors: the per-process CPU list ([ProcessCpuSampler]) and
 * the game FPS history ([FpsInteractor]).
 *
 * The old version mirrored the bus into many MutableStateFlows inside a loop;
 * that loop and those flows are gone.
 */
@Singleton
class MonitorTelemetry @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bus: TelemetryBus,
    private val fpsInteractor: FpsInteractor,
) : MonitorDataSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Replaces the old `top` fork-only sampler; see [ProcessCpuSampler]. */
    private val processCpu = ProcessCpuSampler(context, topN = 25)

    // A3: the snapshot IS the bus snapshot. No mirroring.
    override val snapshot: StateFlow<TelemetrySnapshot>
        get() = bus.snapshot

    override val fpsHistory: StateFlow<List<Float>>
        get() = fpsInteractor.realFpsHistory

    private val _topProcesses = MutableStateFlow<List<ProcessUsage>>(emptyList())
    override val topProcesses: StateFlow<List<ProcessUsage>> = _topProcesses.asStateFlow()

    fun start() {
        if (job?.isActive == true) return
        bus.start()
        job = scope.launch {
            // The sampler owns the process list (its unique work).
            launch {
                processCpu.reset() // stale baseline after a stop/start pause
                while (isActive) {
                    runCatching {
                        val procs = processCpu.sample()
                        // Priming tick yields nothing; don't blank a good list.
                        if (procs.isNotEmpty()) _topProcesses.value = procs
                    }
                    delay(1000)
                }
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        bus.stop()
    }
}