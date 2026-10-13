package io.github.romanvht.byedpi.services

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.romanvht.byedpi.data.*
import io.github.romanvht.byedpi.ewenloy.tgws.EwenloyTgWsServiceExtension
import io.github.romanvht.byedpi.receiver.ServiceActionReceiver
import io.github.romanvht.byedpi.utility.getPreferences
import io.github.romanvht.byedpi.utility.mode

@RequiresApi(Build.VERSION_CODES.N)
class QuickTileService : TileService() {

    companion object {
        private const val TAG = "QuickTileService"
        private var instance: QuickTileService? = null
        @Volatile private var lastClickMs = 0L
        private const val DEBOUNCE_MS = 2000L

        fun updateTile() {
            instance?.updateStatus()
        }

        // Что переключает плитка: bbd (по умолчанию), tgws или both
        const val QUICK_TILE_MODE_KEY = "quick_tile_mode"
        const val QUICK_TILE_BBD = "bbd"
        const val QUICK_TILE_TG_WS = "tgws"
        const val QUICK_TILE_BOTH = "both"
    }

    private var appTile: Tile? = null
    private val tgWsExt = EwenloyTgWsServiceExtension()

    override fun onStartListening() {
        super.onStartListening()
        instance = this
        appTile = qsTile

        ServiceManager.refresh(this)
        tgWsExt.initialize(this, getPreferences())
        updateStatus()
    }

    override fun onStopListening() {
        super.onStopListening()
        instance = null
        appTile = null
    }

    override fun onClick() {
        super.onClick()
        ServiceManager.refresh(this)

        val now = System.currentTimeMillis()
        if (now - lastClickMs < DEBOUNCE_MS) {
            Log.d(TAG, "Tile click debounced")
            return
        }
        lastClickMs = now

        handleClick()
    }

    private fun handleClick() {
        when (tileMode()) {
            QUICK_TILE_TG_WS -> toggleTgWs()
            QUICK_TILE_BOTH -> toggleBoth()
            else -> toggleBbd()
        }
    }

    private fun tileMode(): String =
        getPreferences().getString(QUICK_TILE_MODE_KEY, QUICK_TILE_BBD)?.lowercase() ?: QUICK_TILE_BBD

    private fun tgWsEnabled(): Boolean =
        getPreferences().getBoolean(EwenloyTgWsServiceExtension.EWENLOY_TG_WS_MODE_KEY, false)

    private fun tgWsRunning(): Boolean = tgWsEnabled() && tgWsExt.isRunning()

    private fun toggleBbd() {
        when (appStatus.first) {
            AppStatus.Halted -> startService(getPreferences().mode())
            AppStatus.Running -> stopService()
        }
    }

    // Повторяет кнопку TG WS в приложении: сначала настройка, затем сервис
    private fun toggleTgWs() {
        val turnOn = !tgWsRunning()
        setTgWsPreference(turnOn)
        sendTgWsAction(turnOn)
    }

    // Тап поднимает сразу ByeDPI и TG WS, второй тап останавливает оба
    private fun toggleBoth() {
        val bbdRunning = appStatus.first == AppStatus.Running
        val turnOn = !(bbdRunning && tgWsRunning())

        if (turnOn) {
            if (!bbdRunning) startService(getPreferences().mode())
            setTgWsPreference(true)
            sendTgWsAction(true)
        } else {
            if (bbdRunning) stopService()
            setTgWsPreference(false)
            sendTgWsAction(false)
        }
    }

    private fun setTgWsPreference(enabled: Boolean) {
        getPreferences().edit()
            .putBoolean(EwenloyTgWsServiceExtension.EWENLOY_TG_WS_MODE_KEY, enabled)
            .apply()
    }

    // TG WS стартует через тот же receiver, что и ByeDPI: путь проверен на Android 12+
    private fun sendTgWsAction(start: Boolean) {
        sendBroadcast(Intent(this, ServiceActionReceiver::class.java)
            .setAction(if (start) TG_WS_START_ACTION else TG_WS_STOP_ACTION))
    }

    private fun startService(mode: Mode) {
        if (mode == Mode.VPN && VpnService.prepare(this) != null) {
            return
        }
        sendBroadcast(Intent(this, ServiceActionReceiver::class.java).setAction(START_ACTION)
            .putExtra(ServiceActionReceiver.EXTRA_MODE, mode.name))
    }

    private fun stopService() {
        sendBroadcast(Intent(this, ServiceActionReceiver::class.java).setAction(STOP_ACTION)
            .putExtra(ServiceActionReceiver.EXTRA_MODE, appStatus.second.name))
        setState(Tile.STATE_INACTIVE)
    }

    private fun updateStatus() {
        val running = when (tileMode()) {
            QUICK_TILE_TG_WS -> tgWsRunning()
            QUICK_TILE_BOTH -> appStatus.first == AppStatus.Running && tgWsRunning()
            else -> appStatus.first == AppStatus.Running
        }
        setState(if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE)
    }

    private fun setState(state: Int) {
        appTile?.apply {
            this.state = state
            updateTile()
        }
    }
}
