package io.github.romanvht.byedpi.utility

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.widget.Toast
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.ewenloy.tgws.EwenloyTgWsServiceExtension
import io.github.romanvht.byedpi.ewenloy.tgws.NativeProxy
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
        // Ядро само знает правильный префикс: dd для обычного MTProto и ee+домен,
        // если включён FakeTLS. Хардкод "dd" ломал бы ссылку при включённом FakeTLS.
        val nativeSecret = try {
            NativeProxy.getSecretWithPrefix()
        } catch (_: Throwable) {
            null
        }
        val fullSecret = nativeSecret?.takeIf { it.length >= 34 } ?: "dd$secret"
        val query = "proxy?server=127.0.0.1&port=${EwenloyTgWsServiceExtension.TG_WS_PORT}&secret=$fullSecret"
        val tgLink = "tg://$query"

        val tgIntent = Intent(Intent.ACTION_VIEW, Uri.parse(tgLink))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // Android 11+ умеет не находить обработчик даже при установленном клиенте,
        // и тогда человек видел просто тост об ошибке. Считаем обработчиков сами.
        val handlers = try {
            context.packageManager.queryIntentActivities(tgIntent, 0)
        } catch (_: Exception) {
            emptyList()
        }

        when {
            handlers.size == 1 -> try {
                context.startActivity(tgIntent)
            } catch (_: ActivityNotFoundException) {
                fallback(context, tgLink, query)
            }

            handlers.size > 1 -> try {
                val chooser = Intent.createChooser(tgIntent, context.getString(R.string.ewenloy_tg_choose_client))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(chooser)
            } catch (_: ActivityNotFoundException) {
                fallback(context, tgLink, query)
            }

            else -> fallback(context, tgLink, query)
        }
    }

    /**
     * Клиента, который понимает tg://, не нашлось: копируем ссылку и пробуем
     * веб-вариант t.me — его подхватит и браузер, и Telegram, если он есть.
     */
    private fun fallback(context: Context, tgLink: String, query: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("MTProto proxy", tgLink))
        } catch (_: Exception) {
        }
        Toast.makeText(context, R.string.ewenloy_tg_link_copied, Toast.LENGTH_LONG).show()

        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/$query"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(webIntent)
        } catch (_: ActivityNotFoundException) {
        }
    }
}
