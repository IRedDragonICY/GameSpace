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
    fun chargingProfileRepository(): com.ireddragonicy.gamespace.charging.ChargingProfileRepository
    fun telemetryBus(): com.ireddragonicy.gamespace.telemetry.TelemetryBus
    fun bannerRepository(): com.ireddragonicy.gamespace.data.banner.GameBannerRepository
    fun sidebarStyleStore(): com.ireddragonicy.gamespace.data.SidebarStyleStore
    fun settingsIO(): com.ireddragonicy.gamespace.data.SettingsIO
    fun perAppSettingStore(): com.ireddragonicy.gamespace.data.PerAppSettingStore
    fun videoSettingStore(): com.ireddragonicy.gamespace.data.VideoSettingStore
    fun activeSessionStore(): com.ireddragonicy.gamespace.data.ActiveSessionStore
    fun panelStateStore(): com.ireddragonicy.gamespace.gamebar.PanelStateStore
    fun themeStore(): com.ireddragonicy.gamespace.ui.theme.ThemeStore
    fun appIconCache(): com.ireddragonicy.gamespace.cache.AppIconCache
    fun bannerBitmapCache(): com.ireddragonicy.gamespace.cache.BannerBitmapCache
    fun installedAppsRepository(): com.ireddragonicy.gamespace.data.repo.InstalledAppsRepository
}
