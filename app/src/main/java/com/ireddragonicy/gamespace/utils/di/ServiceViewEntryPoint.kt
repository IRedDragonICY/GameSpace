package com.ireddragonicy.gamespace.utils.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.ireddragonicy.gamespace.data.AppSettings
import com.ireddragonicy.gamespace.data.SystemSettings
import com.ireddragonicy.gamespace.thermal.PerfTuner
import com.ireddragonicy.gamespace.utils.GameModeUtils
import com.ireddragonicy.gamespace.utils.ScreenUtils


@EntryPoint
@InstallIn(SingletonComponent::class)
interface ServiceViewEntryPoint {
    fun appSettings(): AppSettings
    fun systemSettings(): SystemSettings
    fun screenUtils(): ScreenUtils
    fun gameModeUtils(): GameModeUtils
    fun perfTuner(): PerfTuner
    fun telemetryBus(): com.ireddragonicy.gamespace.telemetry.TelemetryBus
}
