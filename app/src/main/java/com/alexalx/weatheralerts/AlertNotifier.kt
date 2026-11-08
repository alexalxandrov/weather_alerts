package com.alexalx.weatheralerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat

object AlertNotifier {
    fun show(context: Context, alert: AlertRecord) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannels(manager)
        val channel = channelFor(alert.category)
        val intent = Intent(context, MainActivity::class.java)
        val pending = PendingIntent.getActivity(context, alert.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("${alert.city}: ${alert.headline}")
            .setContentText("${alert.category} · ${alert.severity}")
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.description.ifBlank { alert.headline }))
            .setContentIntent(pending).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_MAX).build()
        manager.notify(alert.id.hashCode(), n)
    }

    fun test(context: Context, category: String) {
        show(context, AlertRecord("test_${category}_${System.currentTimeMillis()}", "local_test",
            "TEST LOCATION", category, "TEST ALERT — $category",
            "This is a local test notification. No real emergency is occurring.", "Test", category))
    }

    fun ensureChannels(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val categories = listOf("Severe Storm", "Tsunami", "Earthquake", "Flood", "Wildfire",
                "Volcano", "Radiological / Nuclear", "Chemical", "Health", "Landslide", "Avalanche",
                "Winter Weather", "Extreme Heat", "Extreme Cold", "Air Quality", "Space Weather",
                "Public Safety", "Weather", "Other")
            categories.forEach { category ->
                val id = channelFor(category)
                if (manager.getNotificationChannel(id) == null) {
                    val c = NotificationChannel(id, category, NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Emergency Alert Aggregator: $category"
                        enableVibration(true)
                        setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), null)
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    }
                    manager.createNotificationChannel(c)
                }
            }
        }
    }

    private fun channelFor(category: String) = "alert_" + category.lowercase().replace(Regex("[^a-z0-9]+"), "_")
}
