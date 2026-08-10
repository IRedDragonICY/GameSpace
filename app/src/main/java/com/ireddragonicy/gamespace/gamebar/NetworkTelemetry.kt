package com.ireddragonicy.gamespace.gamebar

import android.content.Context
import androidx.compose.runtime.MutableState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner for ping + speed (Phase-1 item pulled forward because
 * TileRepository depends on it). One start()/stop() pair; the two underlying
 * monitors keep their loops for now — merging into one ticker is a follow-up.
 */
@Singleton
class NetworkTelemetry @Inject constructor() {

    private val ping = NetworkPingMonitor()
    private val speed = NetworkSpeedMonitor()

    val latencyMs: MutableState<Int> get() = ping.latencyMs
    val allServerPings: MutableState<List<Pair<String, Int>>> get() = ping.allServerPings
    val rxSpeedKbps: MutableState<Float> get() = speed.rxSpeedKbps
    val txSpeedKbps: MutableState<Float> get() = speed.txSpeedKbps

    fun start(context: Context, gameUid: Int) {
        ping.start(context, gameUid)
        speed.start(gameUid)
    }
    fun stop() {
        ping.stop()
        speed.stop()
    }
}
