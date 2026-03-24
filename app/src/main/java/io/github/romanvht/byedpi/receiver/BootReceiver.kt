package io.github.romanvht.byedpi.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.utility.getPreferences
import io.github.romanvht.byedpi.utility.mode

/**
 * Автозапуск вызывает тот же путь, что и кнопка в приложении: [ServiceManager.start]
 * → ByeDPI + (при включённой настройке) TG WS на :1082. Без отдельной ветки.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val appCtx = context.applicationContext
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_REBOOT,
            ACTION_QUICKBOOT_POWERON,
            -> {
                if (SystemClock.elapsedRealtime() > BOOT_MAX_ELAPSED_MS) {
                    Log.d(TAG, "Skip autostart: boot intent too late (${SystemClock.elapsedRealtime()} ms)")
                    return
                }
                Handler(Looper.getMainLooper()).postDelayed(
                    { tryAutostart(appCtx, reason = "boot") },
                    START_DELAY_AFTER_BOOT_MS,
                )
            }
            Intent.ACTION_USER_UNLOCKED -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    tryAutostart(appCtx, reason = "user_unlocked")
                }
            }
            else -> {}
        }
    }

    private fun tryAutostart(context: Context, reason: String) {
        runCatching {
            val preferences = context.getPreferences()
            if (!preferences.getBoolean("autostart", false)) return

            when (preferences.mode()) {
                Mode.VPN -> {
                    if (VpnService.prepare(context) == null) {
                        Log.i(TAG, "Autostart VPN ($reason)")
                        ServiceManager.start(context, Mode.VPN)
                    } else {
                        Log.d(TAG, "Autostart VPN skipped: need user approval (prepare != null)")
                    }
                }
                Mode.Proxy -> {
                    Log.i(TAG, "Autostart Proxy ($reason)")
                    ServiceManager.start(context, Mode.Proxy)
                }
            }
        }.onFailure { Log.e(TAG, "Autostart failed ($reason)", it) }
    }

    companion object {
        private val TAG: String = BootReceiver::class.java.simpleName
        private const val ACTION_QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        private const val BOOT_MAX_ELAPSED_MS = 15L * 60L * 1000L
        private const val START_DELAY_AFTER_BOOT_MS = 2000L
    }
}