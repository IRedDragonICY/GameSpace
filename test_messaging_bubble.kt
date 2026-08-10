import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon

fun launchAppInBubbleMode(context: Context, packageName: String) {
    try {
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
        if (shortcutManager != null) {
            val shortcut = ShortcutInfo.Builder(context, shortcutId)
                .setShortLabel(appInfo.loadLabel(pm))
                .setIcon(icon)
                .setIntent(launchIntent)
                .setLongLived(true)
                .build()
            shortcutManager.addDynamicShortcuts(listOf(shortcut))
        }
        
        val bubbleData = Notification.BubbleMetadata.Builder(pendingIntent, icon)
            .setDesiredHeight(600)
            .setAutoExpandBubble(true)
            .setSuppressNotification(true)
            .build()

        val channelId = "gamespace_bubbles"
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        
        if (notificationManager.getNotificationChannel(channelId) == null) {
            val channel = android.app.NotificationChannel(
                channelId, 
                "GameSpace Bubbles", 
                android.app.NotificationManager.IMPORTANCE_HIGH
            )
            channel.setAllowBubbles(true)
            notificationManager.createNotificationChannel(channel)
        }

        val person = Person.Builder()
            .setName(appInfo.loadLabel(pm))
            .setIcon(icon)
            .build()

        val notification = Notification.Builder(context, channelId)
            .setStyle(Notification.MessagingStyle(person)
                .addMessage("Running in Bubble Mode", System.currentTimeMillis(), person))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setSmallIcon(com.ireddragonicy.gamespace.R.drawable.ic_bubble)
            .setShortcutId(shortcutId) 
            .setBubbleMetadata(bubbleData)
            .build()

        notificationManager.notify(packageName.hashCode(), notification)
    } catch (e: Exception) {
        android.util.Log.e("GamePanelCard", "Failed to launch \$packageName in Bubble mode", e)
    }
}
