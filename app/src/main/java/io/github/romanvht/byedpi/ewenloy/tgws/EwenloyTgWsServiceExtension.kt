package io.github.romanvht.byedpi.ewenloy.tgws

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import io.github.romanvht.byedpi.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EwenloyTgWsServiceExtension {
    private var initialized = false
    private var enabled = false
    @Volatile private var running = false
    private var proxyServer: EwenloyTgWsProxyServer? = null
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context, preferences: SharedPreferences) {
        this.preferences = preferences
        enabled = preferences.getBoolean(EWENLOY_TG_WS_MODE_KEY, false)
        updateRuntimeStatus(TG_STATUS_DISABLED)
        initialized = true
    }

    fun start(preferences: SharedPreferences) {
        if (!initialized) return
        this.preferences = preferences
        enabled = preferences.getBoolean(EWENLOY_TG_WS_MODE_KEY, false)
        if (!enabled) {
            updateRuntimeStatus(TG_STATUS_DISABLED)
            return
        }
        val server = EwenloyTgWsProxyServer(
            host = "127.0.0.1",
            listenPort = TG_WS_PORT,
            onRouteStatus = { status -> updateRuntimeStatus(status) },
            onStats = { message -> updateDiagnostics(message) },
        )
        server.start()

        if (!server.isRunning()) {
            Log.e(TAG, "TG WS proxy failed to bind 127.0.0.1:$TG_WS_PORT — falling back to normal mode")
            proxyServer = null
            running = false
            enabled = false
            updateRuntimeStatus(TG_STATUS_DISABLED)
            updateDiagnostics("FAILED to bind 127.0.0.1:$TG_WS_PORT")
            return
        }

        proxyServer = server
        running = true
        updateRuntimeStatus(TG_STATUS_IDLE)
        updateDiagnostics("started ws=127.0.0.1:$TG_WS_PORT (standalone, no ByeDPI hop)")
        server.warmup()
    }

    fun stop() {
        runCatching { proxyServer?.stop() }
        proxyServer = null
        running = false
        updateRuntimeStatus(TG_STATUS_DISABLED)
        updateDiagnostics("stopped")
    }

    fun statusTextRes(): Int =
        when {
            !running || !enabled -> R.string.tg_ws_status_disabled
            runtimeStatus() == TG_STATUS_WS -> R.string.tg_ws_status_ws
            runtimeStatus() == TG_STATUS_DIRECT -> R.string.tg_ws_status_direct
            else -> R.string.tg_ws_status_idle
        }

    fun isEnabled(): Boolean = enabled
    fun isRunning(): Boolean = running

    fun runtimeStatus(): String =
        preferences?.getString(EWENLOY_TG_RUNTIME_STATUS_KEY, TG_STATUS_DISABLED) ?: TG_STATUS_DISABLED

    private fun updateRuntimeStatus(status: String) {
        runCatching { preferences?.edit(commit = true) { putString(EWENLOY_TG_RUNTIME_STATUS_KEY, status) } }
    }

    private fun updateDiagnostics(message: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        runCatching { preferences?.edit(commit = true) { putString(EWENLOY_TG_DIAGNOSTICS_KEY, "[$ts] $message") } }
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
