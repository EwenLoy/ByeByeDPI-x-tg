package io.github.romanvht.byedpi.services

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.lifecycleScope
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.activities.MainActivity
import io.github.romanvht.byedpi.core.ByeDpiProxy
import io.github.romanvht.byedpi.core.ByeDpiProxyPreferences
import io.github.romanvht.byedpi.core.TProxyService
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
import java.io.File

class ByeDpiVpnService : LifecycleVpnService() {
    private val byeDpiProxy = ByeDpiProxy()
    private val tgWsServiceExtension = EwenloyTgWsServiceExtension()
    private var proxyJob: Job? = null
    private var notificationRefreshJob: Job? = null
    private var tunFd: ParcelFileDescriptor? = null
    private val mutex = Mutex()

    @Volatile
    private var userRequestedShutdown = false

    companion object {
        private val TAG: String = ByeDpiVpnService::class.java.simpleName
        private const val FOREGROUND_SERVICE_ID: Int = 1
        private const val PAUSE_NOTIFICATION_ID: Int = 3
        private const val NOTIFICATION_CHANNEL_ID: String = "ByeDPIVpn"

        private var status: ServiceStatus = ServiceStatus.Disconnected
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { tgWsServiceExtension.initialize(this, getPreferences()) }
        registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.vpn_channel_name,
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { tunFd?.close() }
        tunFd = null
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
                    try {
                        if (prepare(this@ByeDpiVpnService) == null) {
                            start()
                        }
                    } catch (e: Exception) { Log.e(TAG, "resume() crashed", e) }
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

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked")
        lifecycleScope.launch {
            try { stop() } catch (e: Exception) { Log.e(TAG, "onRevoke stop crashed", e) }
        }
    }

    private suspend fun start() {
        Log.i(TAG, "Starting")

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(PAUSE_NOTIFICATION_ID)

        if (status == ServiceStatus.Connected) {
            Log.w(TAG, "VPN already connected")
            return
        }

        try {
            mutex.withLock {
                if (status == ServiceStatus.Connected) {
                    Log.w(TAG, "VPN already connected")
                    return@withLock
                }
                userRequestedShutdown = false
                startProxy()
                delay(400)
                runCatching { tgWsServiceExtension.start(getPreferences()) }
                startTun2Socks()
                startNotificationRefresh()
                updateStatus(ServiceStatus.Connected)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
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
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
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
                    runCatching { stopTun2Socks() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop VPN", e)
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

        val preferences = getByeDpiPreferences()

        proxyJob = lifecycleScope.launch(Dispatchers.IO) {
            val code = try {
                byeDpiProxy.startProxy(preferences)
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

            Log.w(TAG, "ByeDPI proxy exited unexpectedly (code=$code), tearing down VPN + TG WS")
            withContext(Dispatchers.Main) {
                runCatching { tgWsServiceExtension.stop() }
                runCatching { stopNotificationRefresh() }
                runCatching { stopTun2Socks() }
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
            runCatching { byeDpiProxy.stopProxy() }
            proxyJob?.cancel()

            val completed = withTimeoutOrNull(2000) {
                proxyJob?.join()
                true
            }

            if (completed == null) {
                Log.w(TAG, "proxy not finish in time, cancelling...")
                runCatching { byeDpiProxy.jniForceClose() }
            }

            proxyJob = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close proxyJob", e)
            proxyJob = null
        }

        Log.i(TAG, "Proxy stopped")
    }

    private fun startTun2Socks() {
        Log.i(TAG, "Starting tun2socks")

        if (tunFd != null) {
            Log.w(TAG, "tunFd still open from previous cycle, closing")
            runCatching { tunFd?.close() }
            tunFd = null
        }

        val sharedPreferences = getPreferences()
        val (ip, port) = sharedPreferences.getProxyIpAndPort()

        val dns = sharedPreferences.getStringNotNull("dns_ip", "8.8.8.8")
        val ipv6 = sharedPreferences.getBoolean("ipv6_enable", false)

        val tun2socksConfig = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: 8500")

            appendLine("misc:")
            appendLine("  task-stack-size: 81920")

            appendLine("socks5:")
            appendLine("  address: $ip")
            appendLine("  port: $port")
            appendLine("  udp: udp")
        }

        val configPath = try {
            File.createTempFile("config", "tmp", cacheDir).apply {
                writeText(tun2socksConfig)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create config file", e)
            throw e
        }

        val fd = createBuilder(dns, ipv6).establish()
            ?: throw IllegalStateException("VPN connection failed")

        this.tunFd = fd

        TProxyService.TProxyStartService(configPath.absolutePath, fd.fd)

        Log.i(TAG, "Tun2Socks started. ip: $ip port: $port")
    }

    private fun stopTun2Socks() {
        Log.i(TAG, "Stopping tun2socks")

        runCatching { TProxyService.TProxyStopService() }

        try {
            File(cacheDir, "config.tmp").delete()
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to delete config file", e)
        }

        try {
            tunFd?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close tunFd", e)
        } finally {
            tunFd = null
        }

        Log.i(TAG, "Tun2socks stopped")
    }

    private fun getByeDpiPreferences(): ByeDpiProxyPreferences =
        ByeDpiProxyPreferences.fromSharedPreferences(getPreferences(), this)

    private fun updateStatus(newStatus: ServiceStatus) {
        Log.d(TAG, "VPN status changed from $status to $newStatus")

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
            Mode.VPN
        )

        runCatching {
            val intent = Intent(
                when (newStatus) {
                    ServiceStatus.Connected -> STARTED_BROADCAST
                    ServiceStatus.Disconnected -> STOPPED_BROADCAST
                    ServiceStatus.Failed -> FAILED_BROADCAST
                }
            )
            intent.putExtra(SENDER, Sender.VPN.ordinal)
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
            R.string.vpn_notification_content,
            tgWsServiceExtension.statusTextRes(),
            ByeDpiVpnService::class.java,
        )

    private fun createNotificationPause() {
        val notification = createPauseNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            R.string.service_paused_text,
            ByeDpiVpnService::class.java,
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

    private fun createBuilder(dns: String, ipv6: Boolean): Builder {
        Log.d(TAG, "DNS: $dns")
        val builder = Builder()
        builder.setSession("ByeDPI")
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )

        builder.addAddress("10.10.10.10", 32)
            .addRoute("0.0.0.0", 0)

        if (ipv6) {
            builder.addAddress("fd00::1", 128)
                .addRoute("::", 0)
        }

        if (dns.isNotBlank()) {
            builder.addDnsServer(dns)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        val preferences = getPreferences()
        val listType = preferences.getStringNotNull("applist_type", "disable")
        val listedApps = preferences.getSelectedApps()

        when (listType) {
            "blacklist" -> {
                for (packageName in listedApps) {
                    try {
                        builder.addDisallowedApplication(packageName)
                    } catch (e: Exception) {
                        Log.e(TAG, "Не удалось добавить приложение $packageName в черный список", e)
                    }
                }

                builder.addDisallowedApplication(applicationContext.packageName)
            }

            "whitelist" -> {
                for (packageName in listedApps) {
                    try {
                        builder.addAllowedApplication(packageName)
                    } catch (e: Exception) {
                        Log.e(TAG, "Не удалось добавить приложение $packageName в белый список", e)
                    }
                }
            }

            "disable" -> {
                builder.addDisallowedApplication(applicationContext.packageName)
            }
        }

        return builder
    }
}
