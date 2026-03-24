package io.github.romanvht.byedpi.services

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.activities.MainActivity
import io.github.romanvht.byedpi.core.ByeDpiProxy
import io.github.romanvht.byedpi.core.ByeDpiProxyPreferences
import io.github.romanvht.byedpi.core.TProxyService
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
import java.io.File

class ByeDpiVpnService : LifecycleVpnService() {
    private val byeDpiProxy = ByeDpiProxy()
    private val tgWsExt = EwenloyTgWsServiceExtension()
    private var proxyJob: Job? = null
    private var notifyJob: Job? = null
    private var tunFd: ParcelFileDescriptor? = null
    private val mutex = Mutex()

    @Volatile private var userStop = false

    private val tgWsPrefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != EwenloyTgWsServiceExtension.EWENLOY_TG_WS_MODE_KEY) return@OnSharedPreferenceChangeListener
        if (appStatus.first != AppStatus.Running || appStatus.second != Mode.VPN) return@OnSharedPreferenceChangeListener
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
        private const val TAG = "ByeDpiVpnService"
        private const val FG_ID = 1
        private const val PAUSE_ID = 3
        private const val CH_ID = "ByeDPIVpn"
        private var status: ServiceStatus = ServiceStatus.Disconnected
    }

    override fun onCreate() {
        super.onCreate()
        try { tgWsExt.initialize(this, getPreferences()) } catch (e: Exception) { Log.e(TAG, "init", e) }
        registerNotificationChannel(this, CH_ID, R.string.vpn_channel_name)
        getPreferences().registerOnSharedPreferenceChangeListener(tgWsPrefListener)
    }

    override fun onDestroy() {
        try { getPreferences().unregisterOnSharedPreferenceChangeListener(tgWsPrefListener) } catch (_: Exception) {}
        super.onDestroy()
        try { tunFd?.close() } catch (_: Exception) {}
        tunFd = null
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
            RESUME_ACTION -> lifecycleScope.launch {
                if (prepare(this@ByeDpiVpnService) == null) doStart()
            }
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

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked")
        if (!isLifecycleValid()) return
        lifecycleScope.launch { doStop(); doFinish() }
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
                startTun2Socks()
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
        try { stopTun2Socks() } catch (e: Exception) { Log.e(TAG, "tun stop", e) }
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
            startForeground(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(FG_ID, n)
        }
    }

    private fun startProxy() {
        proxyJob?.cancel()
        proxyJob = null

        val preferences = ByeDpiProxyPreferences.fromSharedPreferences(getPreferences(), this)
        proxyJob = lifecycleScope.launch(Dispatchers.IO) {
            val code = try {
                byeDpiProxy.startProxy(preferences)
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
        try { byeDpiProxy.stopProxy() } catch (e: Exception) { Log.e(TAG, "JNI stop", e) }
        proxyJob?.cancel()
        proxyJob = null
    }

    private fun startTun2Socks() {
        try { tunFd?.close() } catch (_: Exception) {}
        tunFd = null

        val prefs = getPreferences()
        val (ip, port) = prefs.getProxyIpAndPort()
        val dns = prefs.getStringNotNull("dns_ip", "8.8.8.8")
        val ipv6 = prefs.getBoolean("ipv6_enable", false)

        val config = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: 8500")
            appendLine("misc:")
            appendLine("  task-stack-size: 81920")
            appendLine("socks5:")
            appendLine("  address: $ip")
            appendLine("  port: $port")
            appendLine("  udp: udp")
        }
        val configFile = File.createTempFile("config", "tmp", cacheDir).apply { writeText(config) }
        val fd = createBuilder(dns, ipv6).establish() ?: throw IllegalStateException("VPN establish failed")
        tunFd = fd
        TProxyService.TProxyStartService(configFile.absolutePath, fd.fd)
    }

    private fun stopTun2Socks() {
        try { TProxyService.TProxyStopService() } catch (e: Exception) { Log.e(TAG, "tproxy stop", e) }
        try { File(cacheDir, "config.tmp").delete() } catch (_: Exception) {}
        try { tunFd?.close() } catch (_: Exception) {}
        tunFd = null
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
            Mode.VPN
        )

        try {
            sendBroadcast(Intent(when (newStatus) {
                ServiceStatus.Connected -> STARTED_BROADCAST
                ServiceStatus.Disconnected -> STOPPED_BROADCAST
                ServiceStatus.Failed -> FAILED_BROADCAST
            }).apply { putExtra(SENDER, Sender.VPN.ordinal) })
        } catch (_: Exception) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try { QuickTileService.updateTile() } catch (_: Exception) {}
        }
    }

    private fun buildNotification(): Notification =
        createConnectionNotification(
            this, CH_ID,
            R.string.notification_title,
            R.string.vpn_notification_content,
            tgWsExt.statusTextRes(),
            ByeDpiVpnService::class.java,
        )

    private fun showPauseNotification() {
        try {
            val n = createPauseNotification(this, CH_ID,
                R.string.notification_title, R.string.service_paused_text,
                ByeDpiVpnService::class.java)
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

    private fun createBuilder(dns: String, ipv6: Boolean): Builder {
        val builder = Builder()
        builder.setSession("ByeDPI")
        builder.setConfigureIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        )
        builder.addAddress("10.10.10.10", 32).addRoute("0.0.0.0", 0)
        if (ipv6) { builder.addAddress("fd00::1", 128).addRoute("::", 0) }
        if (dns.isNotBlank()) { builder.addDnsServer(dns) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { builder.setMetered(false) }

        val prefs = getPreferences()
        val listType = prefs.getStringNotNull("applist_type", "disable")
        val apps = prefs.getSelectedApps()
        when (listType) {
            "blacklist" -> {
                for (pkg in apps) {
                    try { builder.addDisallowedApplication(pkg) } catch (_: Exception) {}
                }
                builder.addDisallowedApplication(applicationContext.packageName)
            }
            "whitelist" -> {
                for (pkg in apps) {
                    try { builder.addAllowedApplication(pkg) } catch (_: Exception) {}
                }
            }
            "disable" -> builder.addDisallowedApplication(applicationContext.packageName)
        }
        return builder
    }
}
