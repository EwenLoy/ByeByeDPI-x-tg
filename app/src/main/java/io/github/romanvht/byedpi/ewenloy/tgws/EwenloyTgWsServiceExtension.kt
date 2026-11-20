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
 * Passes all TG WS settings from SharedPreferences into the foreground service Intent.
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
        val poolSize = preferences.getString(PREF_POOL_SIZE, DEF_POOL)?.toIntOrNull() ?: DEF_POOL_INT
        val bufferSizeKb = preferences.getString(PREF_BUFFER_SIZE, "256")?.toIntOrNull() ?: 256
        val cfEnabled = preferences.getBoolean(PREF_CF_ENABLED, true)
        val cfH2Enabled = preferences.getBoolean(PREF_CF_H2, true)
        val cfDomain = if (cfEnabled) (preferences.getString(PREF_CF_DOMAIN, "") ?: "") else ""
        val cfWorkerDomains = preferences.getString(PREF_CF_WORKER_DOMAINS, "") ?: ""
        val fakeTlsDomain = preferences.getString(PREF_FAKETLS_DOMAIN, "") ?: ""
        // По умолчанию — включено: соединения к Cloudflare идут на 80-й порт
        // вместо TLS 443. Помогает там, где DPI режет TLS Handshake.
        val disableSecure = preferences.getBoolean(PREF_DISABLE_SECURE, DEF_DISABLE_SECURE)
        val testDc = preferences.getBoolean(PREF_TEST_DC, false)
        val proxyProtocol = preferences.getBoolean(PREF_PROXY_PROTOCOL, false)
        val verbose = preferences.getBoolean(PREF_VERBOSE, false)
        val dcIps = buildDcIps(preferences)

        ContextCompat.startForegroundService(ctx,
            Intent(ctx, TgWsProxyService::class.java).apply {
                action = TgWsProxyService.ACTION_START
                putExtra(TgWsProxyService.EXTRA_BIND_IP, "127.0.0.1")
                putExtra(TgWsProxyService.EXTRA_PORT, TG_WS_PORT)
                putExtra(TgWsProxyService.EXTRA_IPS, dcIps)
                putExtra(TgWsProxyService.EXTRA_POOL_SIZE, poolSize)
                putExtra(TgWsProxyService.EXTRA_BUFFER_SIZE_KB, bufferSizeKb)
                putExtra(TgWsProxyService.EXTRA_CFPROXY_ENABLED, cfEnabled)
                putExtra(TgWsProxyService.EXTRA_CFPROXY_H2, cfH2Enabled)
                putExtra(TgWsProxyService.EXTRA_CFPROXY_DOMAIN, cfDomain)
                putExtra(TgWsProxyService.EXTRA_CF_WORKER_DOMAINS, cfWorkerDomains)
                putExtra(TgWsProxyService.EXTRA_FAKETLS_DOMAIN, fakeTlsDomain)
                putExtra(TgWsProxyService.EXTRA_DISABLE_SECURE, disableSecure)
                putExtra(TgWsProxyService.EXTRA_TEST_DC, testDc)
                putExtra(TgWsProxyService.EXTRA_PROXY_PROTOCOL, proxyProtocol)
                putExtra(TgWsProxyService.EXTRA_VERBOSE, verbose)
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
        // Не stop()+start(): это гонка. stop() выставляет stopInProgress и держит
        // его, пока идёт остановка, а пришедший следом ACTION_START молча
        // отбрасывается — прокси «долго не стартует» или не стартует вовсе.
        // Сервис сам увидит, что уже работает, и применит настройки на месте.
        start(preferences)
    }

    fun stop(clearPreference: Boolean = true) {
        val ctx = context ?: return
        ctx.startService(Intent(ctx, TgWsProxyService::class.java).apply {
            action = TgWsProxyService.ACTION_STOP
        })
        if (clearPreference) {
            preferences?.edit(commit = true) { putBoolean(EWENLOY_TG_WS_MODE_KEY, false) }
            enabled = false
        }
        writeStatus(TG_STATUS_DISABLED)
        Log.i(TAG, "TgWsProxyService stop requested")
    }

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

    /**
     * Список DC для ядра. При включённом Cloudflare список НЕ передаём.
     *
     * Ядро считает DC «настроенным», если для него есть IP, и тогда ведёт
     * соединение напрямую в датацентр Telegram, минуя Cloudflare. В сетях, где
     * прямой доступ к DC режется DPI, это ломает Telegram целиком — ровно то,
     * ради чего включают Cloudflare. Раньше здесь стоял такой же ранний выход;
     * при рефакторинге 2.0.8 он потерялся.
     */
    private fun buildDcIps(prefs: SharedPreferences): String {
        if (prefs.getBoolean(PREF_CF_ENABLED, true)) return ""
        val isExp = prefs.getBoolean(PREF_EXPERIMENTAL_MODE, false)
        return buildList {
            appendDc(1, prefs.getString(PREF_DC1, "149.154.175.50") ?: "")
            appendDc(2, prefs.getString(PREF_DC2, "149.154.167.51") ?: "")
            appendDc(3, prefs.getString(PREF_DC3, "149.154.175.100") ?: "")
            appendDc(4, prefs.getString(PREF_DC4, "149.154.167.91") ?: "")
            if (isExp) {
                appendDc(5,    prefs.getString(PREF_DC5, "149.154.171.5") ?: "")
                appendDc(203,  prefs.getString(PREF_DC203, "91.105.192.100") ?: "")
                appendDc(-1,   prefs.getString(PREF_DC1M, "149.154.175.50") ?: "")
                appendDc(-2,   prefs.getString(PREF_DC2M, "149.154.167.51") ?: "")
                appendDc(-3,   prefs.getString(PREF_DC3M, "149.154.175.100") ?: "")
                appendDc(-4,   prefs.getString(PREF_DC4M, "149.154.167.91") ?: "")
                appendDc(-5,   prefs.getString(PREF_DC5M, "149.154.171.5") ?: "")
                appendDc(-203, prefs.getString(PREF_DC203M, "91.105.192.100") ?: "")
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
        private const val DEF_POOL = "4"
        private const val DEF_POOL_INT = 4

        /** По умолчанию TLS к Cloudflare выключен (порт 80) — так проходит на сетях с DPI. */
        const val DEF_DISABLE_SECURE = true

        /** Секрет MTProto: существующий 32-символьный hex или новый. */
        fun ensureSecret(prefs: SharedPreferences): String {
            val existing = prefs.getString(PREF_SECRET_KEY, "")?.trim() ?: ""
            if (existing.length == 32 && existing.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' })
                return existing
            val generated = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
            prefs.edit(commit = true) { putString(PREF_SECRET_KEY, generated) }
            Log.i(TAG, "Generated new MTProto secret")
            return generated
        }

        const val EWENLOY_TG_WS_MODE_KEY       = "ewenloy_tg_ws_mode_enabled"
        const val EWENLOY_TG_RUNTIME_STATUS_KEY = "ewenloy_tg_runtime_status"
        const val EWENLOY_TG_DIAGNOSTICS_KEY    = "ewenloy_tg_diagnostics"
        const val EWENLOY_TG_SECRET_KEY         = "tg_ws_secret_key"

        const val PREF_SECRET_KEY        = "tg_ws_secret_key"
        const val PREF_POOL_SIZE         = "tg_ws_pool_size"
        const val PREF_BUFFER_SIZE       = "tg_ws_buffer_size"
        const val PREF_CF_ENABLED        = "tg_ws_cf_enabled"
        const val PREF_CF_H2             = "tg_ws_cf_h2"
        const val PREF_CF_DOMAIN         = "tg_ws_cf_domain"
        const val PREF_CF_WORKER_DOMAINS = "tg_ws_cf_worker_domains"
        const val PREF_FAKETLS_DOMAIN    = "tg_ws_faketls_domain"
        const val PREF_DISABLE_SECURE    = "tg_ws_disable_secure"
        const val PREF_TEST_DC           = "tg_ws_test_dc"
        const val PREF_PROXY_PROTOCOL    = "tg_ws_proxy_protocol"
        const val PREF_VERBOSE           = "tg_ws_verbose"
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
    }
}
