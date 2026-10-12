package io.github.romanvht.byedpi.utility

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.activities.MainActivity
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.data.PAUSE_ACTION
import io.github.romanvht.byedpi.data.RESUME_ACTION
import io.github.romanvht.byedpi.data.STOP_ACTION
import io.github.romanvht.byedpi.receiver.ServiceActionReceiver

fun registerNotificationChannel(context: Context, id: String, @StringRes name: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val channel = NotificationChannel(
            id,
            context.getString(name),
            NotificationManager.IMPORTANCE_DEFAULT
        )
        channel.enableLights(false)
        channel.enableVibration(false)
        channel.setShowBadge(false)

        manager.createNotificationChannel(channel)
    }
}

fun createConnectionNotification(
    context: Context,
    channelId: String,
    @StringRes title: Int,
    @StringRes content: Int,
    mode: Mode,
    statusLine: String? = null,
): Notification {
    val baseText = context.getString(content)
    val bigText = if (!statusLine.isNullOrBlank()) "$baseText\n$statusLine" else baseText
    return NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setSilent(true)
        .setContentTitle(context.getString(title))
        .setContentText(baseText)
        // Развёрнутая шторка показывает и режим, и строку Telegram WS с трафиком
        .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
        .addAction(0, context.getString(R.string.service_pause_btn),
            PendingIntent.getBroadcast(
                context,
                mode.ordinal,
                Intent(context, ServiceActionReceiver::class.java).setAction(PAUSE_ACTION)
                    .putExtra(ServiceActionReceiver.EXTRA_MODE, mode.name)
                    .putExtra(ServiceActionReceiver.EXTRA_PID, Process.myPid()),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        .addAction(0, context.getString(R.string.service_stop_btn),
            PendingIntent.getBroadcast(
                context,
                mode.ordinal,
                Intent(context, ServiceActionReceiver::class.java).setAction(STOP_ACTION)
                    .putExtra(ServiceActionReceiver.EXTRA_MODE, mode.name)
                    .putExtra(ServiceActionReceiver.EXTRA_PID, Process.myPid()),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()
}

fun createPauseNotification(
    context: Context,
    channelId: String,
    @StringRes title: Int,
    @StringRes content: Int,
    mode: Mode,
): Notification =
    NotificationCompat.Builder(context, channelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setSilent(true)
        .setContentTitle(context.getString(title))
        .setContentText(context.getString(content))
        .addAction(0, context.getString(R.string.service_start_btn),
            PendingIntent.getBroadcast(
                context,
                mode.ordinal,
                Intent(context, ServiceActionReceiver::class.java).setAction(RESUME_ACTION)
                    .putExtra(ServiceActionReceiver.EXTRA_MODE, mode.name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        )
        .setContentIntent(
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )
        .build()
