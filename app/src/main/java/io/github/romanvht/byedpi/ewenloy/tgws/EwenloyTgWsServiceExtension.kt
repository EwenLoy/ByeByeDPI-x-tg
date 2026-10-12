package io.github.romanvht.byedpi.ewenloy.tgws

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import io.github.romanvht.byedpi.R

/**
 * Bridge between ByeDPI services and TgWsProxyService.
 * Mirrors ProxyController from tg-ws-proxy-android — starts/stops via Intent.
 */
class EwenloyTgWsServiceExtension {

    private var initialized = false
    private var enabled = false
    private var preferences: SharedPreferences? = null
    private var context: Context? = null

    fun initialize(context: Context, preferences: SharedPreferences) {
        this.context = context
        this.preferences = preferences
        enabled = preferences.getBoolean(EWENLOY_TG_WS_MODE_KEY, false)
        initialized = true
    }

    fun start(preferences: SharedPreferences) {
        if (!initialized) return
        this.preferences = preferences
        enabled = preferences.getBoolean(EWENLOY_TG_WS_MODE_KEY, false)
        if (!enabled) { writeStatus(TG_STATUS_DISABLED); return }

        val ctx = context ?: return
        val secret = ensureSecret(preferences)
        val poolSize = preferences.getString(PREF_POOL_SIZE, DEFAULT_POOL_SIZE.toString())?.toIntOrNull() ?: DEFAULT_POOL_SIZE
        val cfEnabled = preferences.getBoolean(PREF_CF_ENABLED, true)
        val cfDomain = if (cfEnabled) (preferences.getString(PREF_CF_DOMAIN, "") ?: "") else ""
        val dcIps = buildDcIps(preferences)

        ContextCompat.startForegroundService(ctx,
            Intent(ctx, TgWsProxyService::class.java).apply {
                action = TgWsProxyService.ACTION_START
                putExtra(TgWsProxyService.EXTRA_BIND_IP, "127.0.0.1")
                putExtra(TgWsProxyService.EXTRA_PORT, TG_WS_PORT)
                putExtra(TgWsProxyService.EXTRA_IPS, dcIps)
                putExtra(TgWsProxyService.EXTRA_POOL_SIZE, poolSize)
                putExtra(TgWsProxyService.EXTRA_CFPROXY_ENABLED, cfEnabled)
                putExtra(TgWsProxyService.EXTRA_CFPROXY_PRIORITY, true)
                putExtra(TgWsProxyService.EXTRA_CFPROXY_DOMAIN, cfDomain)
                putExtra(TgWsProxyService.EXTRA_SECRET_KEY, secret)
            }
        )
        Log.i(TAG, "TgWsProxyService start requested on port $TG_WS_PORT")
    }

    fun refreshFromPreferences(preferences: SharedPreferences) {
        if (!initialized) return
        this.preferences = preferences
        val nowEnabled = preferences.getBoolean(EWENLOY_TG_WS_MODE_KEY, false)
        if (!nowEnabled) { if (isRunning()) stop(); return }
        if (!isRunning()) start(preferences) else stop().also { start(preferences) }
    }

    fun stop() {
        val ctx = context ?: return
        ctx.startService(Intent(ctx, TgWsProxyService::class.java).apply {
            action = TgWsProxyService.ACTION_STOP
        })
        writeStatus(TG_STATUS_DISABLED)
        Log.i(TAG, "TgWsProxyService stop requested")
    }

    /**
     * Многострочная сводка для единого уведомления ByeDPI:
     * "Telegram: WS туннель активен\nТрафик: 1.2MB · 3 сесс."
     */
    fun statusLine(): String {
        val ctx = context ?: return ""
        val prefOn = preferences?.getBoolean(EWENLOY_TG_WS_MODE_KEY, false) ?: false
        if (!prefOn) return ctx.getString(R.string.tg_ws_status_disabled)
        val baseRes = when (readStatus()) {
            TG_STATUS_WS     -> R.string.tg_ws_status_ws
            TG_STATUS_DIRECT -> R.string.tg_ws_status_direct
            else             -> R.string.tg_ws_status_idle
        }
        return buildString {
            append(ctx.getString(baseRes))
            TgWsProxyService.lastTraffic.value?.let { append('\n').append(it) }
        }
    }

    fun isEnabled() = enabled
    fun isRunning() = TgWsProxyService.isRunning.value

    // ---- helpers ----

    private fun ensureSecret(prefs: SharedPreferences): String {
        val existing = prefs.getString(PREF_SECRET_KEY, "")?.trim() ?: ""
        if (existing.length == 32 && existing.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' })
            return existing
        val generated = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        prefs.edit(commit = true) { putString(PREF_SECRET_KEY, generated) }
        Log.i(TAG, "Generated new MTProto secret")
        return generated
    }

    private fun buildDcIps(prefs: SharedPreferences): String {
        if (prefs.getBoolean(PREF_CF_ENABLED, true)) return ""
        val isExp = prefs.getBoolean(PREF_EXPERIMENTAL_MODE, false)
        return buildList {
            appendDc(1, prefs.getString(PREF_DC1, "") ?: "")
            appendDc(2, prefs.getString(PREF_DC2, "") ?: "")
            appendDc(3, prefs.getString(PREF_DC3, "") ?: "")
            appendDc(4, prefs.getString(PREF_DC4, "") ?: "")
            if (isExp) {
                appendDc(5,    prefs.getString(PREF_DC5, "") ?: "")
                appendDc(203,  prefs.getString(PREF_DC203, "") ?: "")
                appendDc(-1,   prefs.getString(PREF_DC1M, "") ?: "")
                appendDc(-2,   prefs.getString(PREF_DC2M, "") ?: "")
                appendDc(-3,   prefs.getString(PREF_DC3M, "") ?: "")
                appendDc(-4,   prefs.getString(PREF_DC4M, "") ?: "")
                appendDc(-5,   prefs.getString(PREF_DC5M, "") ?: "")
                appendDc(-203, prefs.getString(PREF_DC203M, "") ?: "")
            }
        }.joinToString(",")
    }

    private fun MutableList<String>.appendDc(dc: Int, value: String) {
        val ip = value.trim(); if (ip.isNotBlank()) add("$dc:$ip")
    }

    private fun readStatus() = preferences?.getString(EWENLOY_TG_RUNTIME_STATUS_KEY, TG_STATUS_DISABLED) ?: TG_STATUS_DISABLED
    private fun writeStatus(s: String) { preferences?.edit(commit = true) { putString(EWENLOY_TG_RUNTIME_STATUS_KEY, s) } }

    companion object {
        private const val TAG = "EwenloyTgWsExt"
        const val TG_WS_PORT = 1082

        const val EWENLOY_TG_WS_MODE_KEY       = "ewenloy_tg_ws_mode_enabled"
        const val EWENLOY_TG_RUNTIME_STATUS_KEY = "ewenloy_tg_runtime_status"
        const val EWENLOY_TG_DIAGNOSTICS_KEY    = "ewenloy_tg_diagnostics"
        const val EWENLOY_TG_SECRET_KEY         = "tg_ws_secret_key"

        const val PREF_SECRET_KEY        = "tg_ws_secret_key"
        const val PREF_POOL_SIZE         = "tg_ws_pool_size"
        const val PREF_CF_ENABLED        = "tg_ws_cf_enabled"
        const val PREF_CF_DOMAIN         = "tg_ws_cf_domain"
        const val PREF_EXPERIMENTAL_MODE = "tg_ws_experimental_mode"
        const val PREF_DC1   = "tg_ws_dc1";  const val PREF_DC2   = "tg_ws_dc2"
        const val PREF_DC3   = "tg_ws_dc3";  const val PREF_DC4   = "tg_ws_dc4"
        const val PREF_DC5   = "tg_ws_dc5";  const val PREF_DC203 = "tg_ws_dc203"
        const val PREF_DC1M  = "tg_ws_dc1m"; const val PREF_DC2M  = "tg_ws_dc2m"
        const val PREF_DC3M  = "tg_ws_dc3m"; const val PREF_DC4M  = "tg_ws_dc4m"
        const val PREF_DC5M  = "tg_ws_dc5m"; const val PREF_DC203M = "tg_ws_dc203m"

        const val TG_STATUS_DISABLED = "disabled"
        const val TG_STATUS_IDLE     = "idle"
        const val TG_STATUS_WS       = "ws"
        const val TG_STATUS_DIRECT   = "direct"

        private const val DEFAULT_POOL_SIZE = 4
    }
}
