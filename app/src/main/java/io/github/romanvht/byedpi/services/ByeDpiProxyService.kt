package io.github.romanvht.byedpi.services

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
import android.os.Build
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.core.ByeDpiProxy
import io.github.romanvht.byedpi.core.ByeDpiProxyPreferences
import io.github.romanvht.byedpi.data.*
import io.github.romanvht.byedpi.ewenloy.tgws.EwenloyTgWsServiceExtension
import io.github.romanvht.byedpi.utility.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

class ByeDpiProxyService : LifecycleService() {
    private var proxy = ByeDpiProxy()
    private val tgWsServiceExtension = EwenloyTgWsServiceExtension()
    private var proxyJob: Job? = null
    private var notificationRefreshJob: Job? = null
    private val mutex = Mutex()

    @Volatile
    private var userRequestedShutdown = false

    companion object {
        private val TAG: String = ByeDpiProxyService::class.java.simpleName
        private const val FOREGROUND_SERVICE_ID: Int = 2
        private const val PAUSE_NOTIFICATION_ID: Int = 3
        private const val NOTIFICATION_CHANNEL_ID: String = "ByeDPI Proxy"

        private var status: ServiceStatus = ServiceStatus.Disconnected
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { tgWsServiceExtension.initialize(this, getPreferences()) }
        registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.proxy_channel_name,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        try {
            startForeground()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground", e)
        }

        return when (val action = intent?.action) {
            START_ACTION -> {
                lifecycleScope.launch {
                    try { start() } catch (e: Exception) { Log.e(TAG, "start() crashed", e) }
                }
                START_STICKY
            }

            STOP_ACTION -> {
                lifecycleScope.launch {
                    try { stop() } catch (e: Exception) { Log.e(TAG, "stop() crashed", e) }
                }
                START_NOT_STICKY
            }

            RESUME_ACTION -> {
                lifecycleScope.launch {
                    try { start() } catch (e: Exception) { Log.e(TAG, "resume() crashed", e) }
                }
                START_STICKY
            }

            PAUSE_ACTION -> {
                lifecycleScope.launch {
                    try {
                        stop()
                        createNotificationPause()
                    } catch (e: Exception) { Log.e(TAG, "pause() crashed", e) }
                }
                START_NOT_STICKY
            }

            else -> {
                Log.w(TAG, "Unknown action: $action")
                START_NOT_STICKY
            }
        }
    }

    private suspend fun start() {
        Log.i(TAG, "Starting")

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(PAUSE_NOTIFICATION_ID)

        if (status == ServiceStatus.Connected) {
            Log.w(TAG, "Proxy already connected")
            return
        }

        try {
            mutex.withLock {
                if (status == ServiceStatus.Connected) {
                    Log.w(TAG, "Proxy already connected")
                    return@withLock
                }
                userRequestedShutdown = false
                startProxy()
                delay(400)
                runCatching { tgWsServiceExtension.start(getPreferences()) }
                startNotificationRefresh()
                updateStatus(ServiceStatus.Connected)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy", e)
            updateStatus(ServiceStatus.Failed)
            try { stop() } catch (e2: Exception) { Log.e(TAG, "Cleanup after failed start also failed", e2) }
        }
    }

    private fun startForeground() {
        val notification: Notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_SERVICE_ID,
                notification,
                FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }
    }

    private suspend fun stop() {
        Log.i(TAG, "Stopping")
        userRequestedShutdown = true

        mutex.withLock {
            try {
                withContext(Dispatchers.IO) {
                    runCatching { tgWsServiceExtension.stop() }
                    runCatching { stopNotificationRefresh() }
                    runCatching { stopProxy() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop proxy", e)
            }
            updateStatus(ServiceStatus.Disconnected)
        }

        userRequestedShutdown = false
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(FOREGROUND_SERVICE_ID)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
        runCatching { stopSelf() }
    }

    private fun startProxy() {
        Log.i(TAG, "Starting proxy")

        if (proxyJob != null) {
            Log.w(TAG, "Proxy job still alive from previous cycle, cleaning up")
            runCatching { proxyJob?.cancel() }
            proxyJob = null
        }

        proxy = ByeDpiProxy()
        val preferences = getByeDpiPreferences()

        proxyJob = lifecycleScope.launch(Dispatchers.IO) {
            val code = try {
                proxy.startProxy(preferences)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "ByeDPI proxy thread ended with error", e)
                -1
            }
            delay(500)

            if (userRequestedShutdown) {
                return@launch
            }

            Log.w(TAG, "ByeDPI proxy exited unexpectedly (code=$code), tearing down TG WS")
            withContext(Dispatchers.Main) {
                runCatching { tgWsServiceExtension.stop() }
                runCatching { stopNotificationRefresh() }
                if (status == ServiceStatus.Connected) {
                    if (code != 0) {
                        updateStatus(ServiceStatus.Failed)
                    } else {
                        updateStatus(ServiceStatus.Disconnected)
                    }
                }
                runCatching {
                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(FOREGROUND_SERVICE_ID)
                }
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(Service.STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                }
                runCatching { stopSelf() }
            }
        }

        Log.i(TAG, "Proxy started")
    }

    private suspend fun stopProxy() {
        Log.i(TAG, "Stopping proxy")

        if (proxyJob == null) {
            Log.w(TAG, "Proxy job already null")
            return
        }

        try {
            runCatching { proxy.stopProxy() }
            proxyJob?.cancel()

            val completed = withTimeoutOrNull(2000) {
                proxyJob?.join()
                true
            }

            if (completed == null) {
                Log.w(TAG, "proxy not finish in time, cancelling...")
                runCatching { proxy.jniForceClose() }
            }

            proxyJob = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close proxyJob", e)
            proxyJob = null
        }

        Log.i(TAG, "Proxy stopped")
    }

    private fun getByeDpiPreferences(): ByeDpiProxyPreferences =
        ByeDpiProxyPreferences.fromSharedPreferences(getPreferences(), this)

    private fun updateStatus(newStatus: ServiceStatus) {
        Log.d(TAG, "Proxy status changed from $status to $newStatus")

        status = newStatus

        setStatus(
            when (newStatus) {
                ServiceStatus.Connected -> AppStatus.Running
                ServiceStatus.Disconnected,
                ServiceStatus.Failed -> {
                    proxyJob = null
                    AppStatus.Halted
                }
            },
            Mode.Proxy
        )

        runCatching {
            val intent = Intent(
                when (newStatus) {
                    ServiceStatus.Connected -> STARTED_BROADCAST
                    ServiceStatus.Disconnected -> STOPPED_BROADCAST
                    ServiceStatus.Failed -> FAILED_BROADCAST
                }
            )
            intent.putExtra(SENDER, Sender.Proxy.ordinal)
            sendBroadcast(intent)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { QuickTileService.updateTile() }
        }
    }

    private fun createNotification(): Notification =
        createConnectionNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            R.string.proxy_notification_content,
            tgWsServiceExtension.statusTextRes(),
            ByeDpiProxyService::class.java,
        )

    private fun createNotificationPause() {
        val notification = createPauseNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            R.string.service_paused_text,
            ByeDpiProxyService::class.java,
        )

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(PAUSE_NOTIFICATION_ID, notification)
    }

    private fun startNotificationRefresh() {
        if (notificationRefreshJob != null) return
        notificationRefreshJob = lifecycleScope.launch {
            while (status == ServiceStatus.Connected) {
                runCatching {
                    val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(FOREGROUND_SERVICE_ID, createNotification())
                }
                delay(1500)
            }
        }
    }

    private fun stopNotificationRefresh() {
        notificationRefreshJob?.cancel()
        notificationRefreshJob = null
    }
}
