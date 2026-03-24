package io.github.romanvht.byedpi.services

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.os.Build
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.core.ByeDpiProxy
import io.github.romanvht.byedpi.core.ByeDpiProxyPreferences
import io.github.romanvht.byedpi.data.*
import io.github.romanvht.byedpi.ewenloy.tgws.EwenloyTgWsServiceExtension
import io.github.romanvht.byedpi.utility.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ByeDpiProxyService : LifecycleService() {
    private var proxy = ByeDpiProxy()
    private val tgWsExt = EwenloyTgWsServiceExtension()
    private var proxyJob: Job? = null
    private var notifyJob: Job? = null
    private val mutex = Mutex()

    @Volatile private var userStop = false

    private val tgWsPrefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != EwenloyTgWsServiceExtension.EWENLOY_TG_WS_MODE_KEY) return@OnSharedPreferenceChangeListener
        if (appStatus.first != AppStatus.Running || appStatus.second != Mode.Proxy) return@OnSharedPreferenceChangeListener
        lifecycleScope.launch {
            try {
                mutex.withLock {
                    if (status != ServiceStatus.Connected) return@withLock
                    tgWsExt.refreshFromPreferences(getPreferences())
                    try {
                        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                        nm.notify(FG_ID, buildNotification())
                    } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "tgws pref sync", e)
            }
        }
    }

    companion object {
        private const val TAG = "ByeDpiProxyService"
        private const val FG_ID = 2
        private const val PAUSE_ID = 3
        private const val CH_ID = "ByeDPI Proxy"
        private var status: ServiceStatus = ServiceStatus.Disconnected
    }

    override fun onCreate() {
        super.onCreate()
        try { tgWsExt.initialize(this, getPreferences()) } catch (e: Exception) { Log.e(TAG, "init", e) }
        registerNotificationChannel(this, CH_ID, R.string.proxy_channel_name)
        getPreferences().registerOnSharedPreferenceChangeListener(tgWsPrefListener)
    }

    override fun onDestroy() {
        try { getPreferences().unregisterOnSharedPreferenceChangeListener(tgWsPrefListener) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (!isLifecycleValid()) {
            Log.w(TAG, "Lifecycle destroyed, ignoring ${intent?.action}")
            return START_NOT_STICKY
        }

        try { doStartForeground() } catch (e: Exception) { Log.e(TAG, "foreground fail", e) }

        when (intent?.action) {
            START_ACTION -> lifecycleScope.launch { doStart() }
            STOP_ACTION -> lifecycleScope.launch { doStop(); doFinish() }
            RESUME_ACTION -> lifecycleScope.launch { doStart() }
            PAUSE_ACTION -> lifecycleScope.launch {
                doStop()
                showPauseNotification()
                doFinish()
            }
            else -> Log.w(TAG, "Unknown action: ${intent?.action}")
        }

        return if (intent?.action in listOf(STOP_ACTION, PAUSE_ACTION))
            START_NOT_STICKY else START_STICKY
    }

    private suspend fun doStart() {
        mutex.withLock {
            if (status == ServiceStatus.Connected) return

            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(PAUSE_ID)

            userStop = false

            try {
                startProxy()
                delay(400)
                try { tgWsExt.start(getPreferences()) } catch (e: Exception) { Log.e(TAG, "tgws start", e) }
                startNotifyRefresh()
                updateStatus(ServiceStatus.Connected)
            } catch (e: Exception) {
                Log.e(TAG, "Start failed", e)
                updateStatus(ServiceStatus.Failed)
                doCleanup()
                doFinish()
            }
        }
    }

    private suspend fun doStop() {
        userStop = true
        mutex.withLock {
            try {
                withContext(Dispatchers.IO) { doCleanup() }
            } catch (e: Exception) {
                Log.e(TAG, "Cleanup error", e)
            }
            updateStatus(ServiceStatus.Disconnected)
        }
        userStop = false
    }

    private fun doCleanup() {
        try { tgWsExt.stop() } catch (e: Exception) { Log.e(TAG, "tgws stop", e) }
        stopNotifyRefresh()
        try { stopProxy() } catch (e: Exception) { Log.e(TAG, "proxy stop", e) }
    }

    private fun doFinish() {
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(FG_ID)
        } catch (_: Exception) {}
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION") stopForeground(true)
            }
        } catch (_: Exception) {}
        try { stopSelf() } catch (_: Exception) {}
    }

    private fun doStartForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(FG_ID, n, FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(FG_ID, n)
        }
    }

    private fun startProxy() {
        proxyJob?.cancel()
        proxyJob = null

        proxy = ByeDpiProxy()
        val preferences = ByeDpiProxyPreferences.fromSharedPreferences(getPreferences(), this)

        proxyJob = lifecycleScope.launch(Dispatchers.IO) {
            val code = try {
                proxy.startProxy(preferences)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Proxy error", e)
                -1
            }
            if (userStop) return@launch
            Log.w(TAG, "Proxy exited unexpectedly code=$code")
            withContext(Dispatchers.Main) {
                if (!isLifecycleValid()) return@withContext
                doCleanup()
                updateStatus(if (code != 0) ServiceStatus.Failed else ServiceStatus.Disconnected)
                doFinish()
            }
        }
    }

    private fun stopProxy() {
        if (proxyJob == null) return
        try { proxy.stopProxy() } catch (e: Exception) { Log.e(TAG, "JNI stop", e) }
        proxyJob?.cancel()
        proxyJob = null
    }

    private fun updateStatus(newStatus: ServiceStatus) {
        status = newStatus

        setStatus(
            when (newStatus) {
                ServiceStatus.Connected -> AppStatus.Running
                ServiceStatus.Disconnected, ServiceStatus.Failed -> {
                    proxyJob = null; AppStatus.Halted
                }
            },
            Mode.Proxy
        )

        try {
            sendBroadcast(Intent(when (newStatus) {
                ServiceStatus.Connected -> STARTED_BROADCAST
                ServiceStatus.Disconnected -> STOPPED_BROADCAST
                ServiceStatus.Failed -> FAILED_BROADCAST
            }).apply { putExtra(SENDER, Sender.Proxy.ordinal) })
        } catch (_: Exception) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try { QuickTileService.updateTile() } catch (_: Exception) {}
        }
    }

    private fun buildNotification(): Notification =
        createConnectionNotification(
            this, CH_ID,
            R.string.notification_title,
            R.string.proxy_notification_content,
            tgWsExt.statusTextRes(),
            ByeDpiProxyService::class.java,
        )

    private fun showPauseNotification() {
        try {
            val n = createPauseNotification(this, CH_ID,
                R.string.notification_title, R.string.service_paused_text,
                ByeDpiProxyService::class.java)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(PAUSE_ID, n)
        } catch (_: Exception) {}
    }

    private fun startNotifyRefresh() {
        notifyJob?.cancel()
        notifyJob = lifecycleScope.launch {
            while (status == ServiceStatus.Connected) {
                try {
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    nm.notify(FG_ID, buildNotification())
                } catch (_: Exception) {}
                delay(1500)
            }
        }
    }

    private fun stopNotifyRefresh() {
        notifyJob?.cancel()
        notifyJob = null
    }

    private fun isLifecycleValid(): Boolean =
        lifecycle.currentState.isAtLeast(Lifecycle.State.INITIALIZED)
}
