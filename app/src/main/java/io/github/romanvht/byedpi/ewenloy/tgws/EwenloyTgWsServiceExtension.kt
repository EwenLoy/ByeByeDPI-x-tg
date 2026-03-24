package io.github.romanvht.byedpi.ewenloy.tgws

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import io.github.romanvht.byedpi.R

class EwenloyTgWsServiceExtension {
    private var initialized = false
    private var enabled = false
    @Volatile private var running = false
    private var proxyServer: EwenloyTgWsProxyServer? = null
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context, preferences: SharedPreferences) {
        this.preferences = preferences
        enabled = preferences.getBoolean(EWENLOY_TG_WS_MODE_KEY, false)
        initialized = true
    }

    fun start(preferences: SharedPreferences) {
        if (!initialized) return
        this.preferences = preferences
        enabled = preferences.getBoolean(EWENLOY_TG_WS_MODE_KEY, false)
        if (!enabled) {
            writeStatus(TG_STATUS_DISABLED)
            return
        }
        if (running) return
        val server = EwenloyTgWsProxyServer(
            host = "127.0.0.1",
            listenPort = TG_WS_PORT,
            onRouteStatus = { status -> writeStatusIfChanged(status) },
            onStats = { },
        )
        server.start()
        if (!server.isRunning()) {
            Log.e(TAG, "TG WS proxy failed to bind 127.0.0.1:$TG_WS_PORT")
            proxyServer = null
            running = false
            // Do not clear user's toggle in prefs — only runtime status
            writeStatus(TG_STATUS_DISABLED)
            return
        }
        proxyServer = server
        running = true
        writeStatus(TG_STATUS_IDLE)
        server.warmup()
    }

    /**
     * When user toggles «Ускорить Telegram через WS» while VPN/Proxy is already running,
     * start or stop the SOCKS5 on :1082 without full reconnect (same idea as Flowseal tray).
     */
    fun refreshFromPreferences(preferences: SharedPreferences) {
        if (!initialized) return
        this.preferences = preferences
        enabled = preferences.getBoolean(EWENLOY_TG_WS_MODE_KEY, false)
        if (!enabled) {
            if (running) stop()
            return
        }
        if (running) return
        start(preferences)
    }

    fun stop() {
        try { proxyServer?.stop() } catch (e: Exception) { Log.e(TAG, "proxy stop", e) }
        proxyServer = null
        running = false
        writeStatus(TG_STATUS_DISABLED)
    }

    /**
     * Shade + notification: always read toggle from prefs (memory `enabled` lags behind Settings).
     */
    fun statusTextRes(): Int {
        val prefOn = preferences?.getBoolean(EWENLOY_TG_WS_MODE_KEY, false) ?: false
        if (!prefOn) return R.string.tg_ws_status_disabled
        if (!running) return R.string.tg_ws_status_idle
        val status = readStatus()
        return when (status) {
            TG_STATUS_WS -> R.string.tg_ws_status_ws
            TG_STATUS_DIRECT -> R.string.tg_ws_status_direct
            TG_STATUS_IDLE -> R.string.tg_ws_status_idle
            TG_STATUS_DISABLED -> R.string.tg_ws_status_idle
            else -> R.string.tg_ws_status_idle
        }
    }

    fun isEnabled(): Boolean = enabled
    fun isRunning(): Boolean = running

    private fun readStatus(): String =
        preferences?.getString(EWENLOY_TG_RUNTIME_STATUS_KEY, TG_STATUS_DISABLED) ?: TG_STATUS_DISABLED

    private fun writeStatus(status: String) {
        try { preferences?.edit(commit = true) { putString(EWENLOY_TG_RUNTIME_STATUS_KEY, status) } }
        catch (_: Exception) {}
    }

    private fun writeStatusIfChanged(status: String) {
        if (readStatus() == status) return
        writeStatus(status)
    }

    companion object {
        private const val TAG = "EwenloyTgWsExt"
        const val TG_WS_PORT = 1082
        const val EWENLOY_TG_WS_MODE_KEY = "ewenloy_tg_ws_mode_enabled"
        const val EWENLOY_TG_RUNTIME_STATUS_KEY = "ewenloy_tg_runtime_status"
        const val TG_STATUS_DISABLED = "disabled"
        const val TG_STATUS_IDLE = "idle"
        const val TG_STATUS_WS = "ws"
        const val TG_STATUS_DIRECT = "direct"
        const val EWENLOY_TG_DIAGNOSTICS_KEY = "ewenloy_tg_diagnostics"
    }
}
