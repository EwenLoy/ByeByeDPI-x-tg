package io.github.romanvht.byedpi.utility

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.ewenloy.tgws.EwenloyTgWsServiceExtension
import io.github.romanvht.byedpi.ewenloy.tgws.TgWsProxyService

/**
 * Ссылка на прокси в Telegram и её открытие в выбранном клиенте.
 *
 * TG WS включён: tg://proxy (MTProto, secret=dd...).
 * Иначе: tg://socks (обычный прокси ByeDPI).
 *
 * Клиента выбирает система: ссылку tg:// обрабатывает любой установленный
 * клиент, а не только те, чьи пакеты перечислены в приложении.
 */
object TelegramProxyLink {

    fun open(context: Context, prefs: SharedPreferences) {
        val wsEnabled = prefs.getBoolean(EwenloyTgWsServiceExtension.EWENLOY_TG_WS_MODE_KEY, false)
        if (wsEnabled && !TgWsProxyService.isRunning.value) {
            Toast.makeText(context, R.string.ewenloy_tg_not_ready, Toast.LENGTH_SHORT).show()
            return
        }

        val uri = if (wsEnabled) {
            val secret = EwenloyTgWsServiceExtension.ensureSecret(prefs)
            Uri.parse(
                "tg://proxy?server=127.0.0.1&port=${EwenloyTgWsServiceExtension.TG_WS_PORT}&secret=dd$secret"
            )
        } else {
            val port = prefs.getString("byedpi_proxy_port", "1080") ?: "1080"
            Uri.parse("tg://socks?server=127.0.0.1&port=$port")
        }

        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val chooser = Intent.createChooser(intent, context.getString(R.string.ewenloy_tg_choose_client))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.ewenloy_tg_open_proxy_error, Toast.LENGTH_SHORT).show()
        }
    }
}
