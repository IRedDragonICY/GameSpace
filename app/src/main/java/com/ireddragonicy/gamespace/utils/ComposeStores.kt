package com.ireddragonicy.gamespace.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.ireddragonicy.gamespace.data.PerAppSettingStore
import com.ireddragonicy.gamespace.data.VideoSettingStore
import com.ireddragonicy.gamespace.ui.theme.ThemeStore
import com.ireddragonicy.gamespace.cache.AppIconCache
import com.ireddragonicy.gamespace.cache.BannerBitmapCache
import com.ireddragonicy.gamespace.utils.di.ServiceViewEntryPoint
import dagger.hilt.android.EntryPointAccessors

private fun ep(context: android.content.Context): ServiceViewEntryPoint =
    EntryPointAccessors.fromApplication(context.applicationContext, ServiceViewEntryPoint::class.java)

@Composable fun rememberPerAppStore(): PerAppSettingStore {
    val ctx = LocalContext.current
    return remember(ctx.applicationContext) { ep(ctx).perAppSettingStore() }
}
@Composable fun rememberVideoStore(): VideoSettingStore {
    val ctx = LocalContext.current
    return remember(ctx.applicationContext) { ep(ctx).videoSettingStore() }
}
@Composable fun rememberThemeStore(): ThemeStore {
    val ctx = LocalContext.current
    return remember(ctx.applicationContext) { ep(ctx).themeStore() }
}
@Composable fun rememberAppIconCache(): AppIconCache {
    val ctx = LocalContext.current
    return remember(ctx.applicationContext) { ep(ctx).appIconCache() }
}
@Composable fun rememberBannerBitmapCache(): BannerBitmapCache {
    val ctx = LocalContext.current
    return remember(ctx.applicationContext) { ep(ctx).bannerBitmapCache() }
}
@Composable fun rememberInstalledAppsRepository(): com.ireddragonicy.gamespace.data.repo.InstalledAppsRepository {
    val ctx = LocalContext.current
    return remember(ctx.applicationContext) { ep(ctx).installedAppsRepository() }
}
