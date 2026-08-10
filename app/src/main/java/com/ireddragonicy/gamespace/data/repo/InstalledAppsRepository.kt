package com.ireddragonicy.gamespace.data.repo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import com.ireddragonicy.gamespace.data.model.AppRef
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single cached source for "installed launchable apps".
 *
 * Before this, five call-sites each ran queryIntentActivities() + label/icon
 * loading — and InlineAppPicker even did it on the MAIN thread inside remember{}.
 * Now it happens once, on Dispatchers.IO, and every reader shares the cached list.
 * The cache self-invalidates on package add/remove/replace.
 */
@Singleton
class InstalledAppsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val pm get() = context.packageManager
    private val cache = AtomicReference<List<AppRef>?>(null)

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) = invalidate()
        }, filter)
    }

    /** Suspending accessor — loads on IO, then caches. */
    suspend fun launchableApps(): List<AppRef> = withContext(Dispatchers.IO) {
        cache.get() ?: loadLaunchable().also { cache.set(it) }
    }

    /** Blocking accessor for non-coroutine callers. Reads cache first. */
    fun launchableAppsBlocking(): List<AppRef> =
        cache.get() ?: loadLaunchable().also { cache.set(it) }

    fun invalidate() { cache.set(null) }

    private fun loadLaunchable(): List<AppRef> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        val seen = HashSet<String>(resolveInfos.size)
        val out = ArrayList<AppRef>(resolveInfos.size)
        for (ri in resolveInfos) {
            val pkg = ri.activityInfo.packageName
            if (!seen.add(pkg)) continue
            val flags = ri.activityInfo.applicationInfo?.flags ?: 0
            out.add(
                AppRef(
                    packageName = pkg,
                    label = ri.loadLabel(pm).toString(),
                    icon = ri.loadIcon(pm),
                    isSystem = (flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            )
        }
        return out.sortedBy { it.label.lowercase() }
    }
}