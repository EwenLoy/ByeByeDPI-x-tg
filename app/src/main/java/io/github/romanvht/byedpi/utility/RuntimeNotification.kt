package io.github.romanvht.byedpi.utility

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.activities.MainActivity
import io.github.romanvht.byedpi.data.AppStatus
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.data.START_ACTION
import io.github.romanvht.byedpi.data.STOP_ACTION
import io.github.romanvht.byedpi.data.TG_WS_START_ACTION
import io.github.romanvht.byedpi.data.TG_WS_STOP_ACTION
import io.github.romanvht.byedpi.ewenloy.tgws.EwenloyTgWsServiceExtension
import io.github.romanvht.byedpi.ewenloy.tgws.TgWsProxyService
import io.github.romanvht.byedpi.receiver.ServiceActionReceiver
import io.github.romanvht.byedpi.services.appStatus

/**
 * Одно уведомление на всё приложение: ByeDPI и TG WS в одной карточке.
 *
 * Кнопки — переключатели: у работающего сервиса «Остановить», у выключенного
 * «Запустить». Обе идут через ServiceActionReceiver, поэтому работают из любого
 * процесса и не зависят от pid сервиса.
 *
 * Режим ByeDPI передаётся явно: сервис работает в отдельном процессе (:native),
 * и общий appStatus там ещё не обновлён в момент старта.
 */
object RuntimeNotification {
    const val ID = 1

    private const val CHANNEL_VPN = "ByeDPIVpn"
    private const val CHANNEL_PROXY = "ByeDPI Proxy"
    private const val CHANNEL_TG = "TG_WS_Proxy_Service_v4"

    private const val REQ_BBD = 10
    private const val REQ_TG = 11

    fun build(context: Context, mode: Mode?, tgMessage: String? = null): Notification {
        val tgRunning = TgWsProxyService.isRunning.value

        val lines = buildList {
            if (mode != null) add(
                context.getString(
                    if (mode == Mode.VPN) R.string.vpn_notification_content
                    else R.string.proxy_notification_content
                )
            )
            when {
                tgRunning -> {
                    add(context.getString(R.string.tg_ws_status_idle))
                    tgMessage?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
                tgMessage != null -> add(tgMessage)
            }
        }

        val channel = when {
            mode == Mode.Proxy -> CHANNEL_PROXY
            mode == Mode.VPN -> CHANNEL_VPN
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

        builder.addAction(
            0,
            context.getString(
                if (mode != null) R.string.notification_stop_bbd else R.string.notification_start_bbd
            ),
            action(
                context,
                REQ_BBD,
                if (mode != null) STOP_ACTION else START_ACTION,
                context.getPreferences().mode().name,
            )
        )

        if (tgRunning) {
            builder.addAction(
                0,
                context.getString(R.string.notification_stop_tg),
                action(context, REQ_TG, TG_WS_STOP_ACTION, null)
            )
        } else if (context.getPreferences()
                .getBoolean(EwenloyTgWsServiceExtension.EWENLOY_TG_WS_MODE_KEY, false)
        ) {
            builder.addAction(
                0,
                context.getString(R.string.notification_start_tg),
                action(context, REQ_TG, TG_WS_START_ACTION, null)
            )
        }

        return builder.build()
    }

    /** Текущий режим ByeDPI по общему состоянию приложения. */
    fun runningMode(): Mode? = if (appStatus.first == AppStatus.Running) appStatus.second else null

    /** Обновляет общее уведомление или убирает его, если ничего не запущено. */
    fun sync(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val mode = runningMode()
        val running = mode != null || TgWsProxyService.isRunning.value
        if (running) manager.notify(ID, build(context, mode)) else manager.cancel(ID)
    }

    private fun action(context: Context, requestCode: Int, action: String, mode: String?): PendingIntent {
        val intent = Intent(context, ServiceActionReceiver::class.java).setAction(action)
        if (mode != null) intent.putExtra(ServiceActionReceiver.EXTRA_MODE, mode)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
