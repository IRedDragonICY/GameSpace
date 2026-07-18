package com.ireddragonicy.gamespace.gamebar

import android.net.TrafficStats
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.*

class NetworkSpeedMonitor {

    companion object {
        private const val TAG = "NetworkSpeedMonitor"
        private const val INTERVAL_MS = 1000L
    }

    val rxSpeedKbps: MutableState<Float> = mutableStateOf(0f)
    val txSpeedKbps: MutableState<Float> = mutableStateOf(0f)

    private var monitorJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(gameUid: Int) {
        stop()
        Log.i(TAG, "Starting speed monitor for UID=$gameUid")

        monitorJob = scope.launch {
            var lastRx = TrafficStats.getUidRxBytes(gameUid)
            var lastTx = TrafficStats.getUidTxBytes(gameUid)

            while (isActive) {
                delay(INTERVAL_MS)
                try {
                    val currentRx = TrafficStats.getUidRxBytes(gameUid)
                    val currentTx = TrafficStats.getUidTxBytes(gameUid)

                    if (currentRx != TrafficStats.UNSUPPORTED.toLong() && currentTx != TrafficStats.UNSUPPORTED.toLong()) {
                        val rxDiff = currentRx - lastRx
                        val txDiff = currentTx - lastTx

                        // Calculate KB/s (byte diff / 1024 / 1 second)
                        rxSpeedKbps.value = (rxDiff / 1024f).coerceAtLeast(0f)
                        txSpeedKbps.value = (txDiff / 1024f).coerceAtLeast(0f)

                        lastRx = currentRx
                        lastTx = currentTx
                    } else {
                        rxSpeedKbps.value = 0f
                        txSpeedKbps.value = 0f
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Speed monitor error", e)
                }
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        rxSpeedKbps.value = 0f
        txSpeedKbps.value = 0f
        Log.i(TAG, "Speed monitor stopped")
    }
}
