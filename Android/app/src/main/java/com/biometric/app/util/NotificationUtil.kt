package com.biometric.app.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.biometric.app.R
import com.biometric.app.ui.LauncherActivity

object NotificationUtil {

    private const val TEST_CHANNEL_ID = "test_notifications"
    private const val TEST_NOTIFICATION_ID = 9999

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Test Alerts"
            val descriptionText = "Channel for testing notification delivery"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(TEST_CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun sendTestNotification(context: Context, isLockScreen: Boolean = false) {
        createNotificationChannels(context)

        val intent = Intent(context, LauncherActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(context, TEST_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logs)
            .setContentTitle("Notification Test 🔔")
            .setContentText(if (isLockScreen) "Lock screen delivery successful! 🔒" else "Notification system is working correctly. ✨")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        with(NotificationManagerCompat.from(context)) {
            notify(TEST_NOTIFICATION_ID, builder.build())
        }
    }
}
