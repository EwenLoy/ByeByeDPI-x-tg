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
 * Ссылка на MTProto-прокси и её открытие в выбранном клиенте Telegram.
 *
 * Всегда tg://proxy: SOCKS-ссылка на 127.0.0.1:1080 в VPN-режиме не работает
 * (Telegram ходит через VPN, а не через локальный SOCKS).
 *
 * Клиента выбирает система, поэтому подходят любые установленные клиенты,
 * а не только те, чьи пакеты перечислены в приложении.
 */
object TelegramProxyLink {

    fun open(context: Context, prefs: SharedPreferences) {
        if (!TgWsProxyService.isRunning.value) {
            Toast.makeText(context, R.string.ewenloy_tg_not_ready, Toast.LENGTH_SHORT).show()
            return
        }

        val secret = EwenloyTgWsServiceExtension.ensureSecret(prefs)
        val uri = Uri.parse(
            "tg://proxy?server=127.0.0.1&port=${EwenloyTgWsServiceExtension.TG_WS_PORT}&secret=dd$secret"
        )

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
