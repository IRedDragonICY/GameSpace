import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.app.Notification
import android.app.PendingIntent

fun launchAppInBubbleMode(context: Context, packageName: String) {
    val pm = context.packageManager
    val launchIntent = pm.getLaunchIntentForPackage(packageName) ?: return
    
    val pendingIntent = PendingIntent.getActivity(
        context,
        0,
        launchIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    val appInfo = pm.getApplicationInfo(packageName, 0)
    val icon = Icon.createWithResource(packageName, appInfo.icon)
    
    val shortcutId = "gamespace_bubble_\$packageName"
    val shortcutManager = context.getSystemService(ShortcutManager::class.java)
    val shortcut = ShortcutInfo.Builder(context, shortcutId)
        .setShortLabel(appInfo.loadLabel(pm))
        .setIcon(icon)
        .setIntent(launchIntent)
        .setLongLived(true)
        .build()
    
    shortcutManager.addDynamicShortcuts(listOf(shortcut))
    
    val bubbleData = Notification.BubbleMetadata.Builder(pendingIntent, icon)
        .setDesiredHeight(600)
        .setAutoExpandBubble(true)
        .setSuppressNotification(true)
        .build()

    // ...
}
