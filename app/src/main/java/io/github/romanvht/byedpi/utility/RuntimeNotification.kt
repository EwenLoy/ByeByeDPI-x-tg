package io.github.romanvht.byedpi.utility

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.core.app.NotificationCompat
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.activities.MainActivity
import io.github.romanvht.byedpi.data.AppStatus
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.data.PAUSE_ACTION
import io.github.romanvht.byedpi.data.STOP_ACTION
import io.github.romanvht.byedpi.ewenloy.tgws.TgWsProxyService
import io.github.romanvht.byedpi.receiver.ServiceActionReceiver
import io.github.romanvht.byedpi.services.appStatus

/**
 * Одно уведомление на всё приложение: ByeDPI и TG WS показываются в одной карточке.
 *
 * Идентификатор общий для всех сервисов, поэтому в шторке всегда одна запись,
 * а не отдельные уведомления на VPN и на прокси.
 */
object RuntimeNotification {
    const val ID = 1

    private const val CHANNEL_VPN = "ByeDPIVpn"
    private const val CHANNEL_PROXY = "ByeDPI Proxy"
    private const val CHANNEL_TG = "TG_WS_Proxy_Service_v4"

    fun build(context: Context, tgMessage: String? = null): Notification {
        val (status, mode) = appStatus
        val bbdRunning = status == AppStatus.Running
        val tgRunning = TgWsProxyService.isRunning.value

        val lines = buildList {
            if (bbdRunning) add(
                context.getString(
                    if (mode == Mode.VPN) R.string.vpn_notification_content
                    else R.string.proxy_notification_content
                )
            )
            when {
                tgMessage != null -> add(tgMessage)
                tgRunning -> add(context.getString(R.string.tg_ws_status_idle))
            }
        }

        val channel = when {
            bbdRunning && mode == Mode.Proxy -> CHANNEL_PROXY
            bbdRunning -> CHANNEL_VPN
            else -> CHANNEL_TG
        }

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_title))
            .setContentText(lines.firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setSilent(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )

        if (bbdRunning) {
            builder.addAction(
                0, context.getString(R.string.service_pause_btn), modeAction(context, mode, PAUSE_ACTION)
            )
            builder.addAction(
                0, context.getString(R.string.service_stop_btn), modeAction(context, mode, STOP_ACTION)
            )
        }
        if (tgRunning) {
            builder.addAction(
                0,
                context.getString(R.string.notification_disconnect),
                PendingIntent.getService(
                    context,
                    0,
                    Intent(context, TgWsProxyService::class.java).setAction(TgWsProxyService.ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
        }
        return builder.build()
    }

    /** Обновляет общее уведомление или убирает его, если ничего не запущено. */
    fun sync(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val running = appStatus.first == AppStatus.Running || TgWsProxyService.isRunning.value
        if (running) manager.notify(ID, build(context)) else manager.cancel(ID)
    }

    private fun modeAction(context: Context, mode: Mode, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            mode.ordinal,
            Intent(context, ServiceActionReceiver::class.java).setAction(action)
                .putExtra(ServiceActionReceiver.EXTRA_MODE, mode.name)
                .putExtra(ServiceActionReceiver.EXTRA_PID, Process.myPid()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
