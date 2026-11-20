package io.github.romanvht.byedpi.ewenloy.tgws

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.activities.MainActivity
import io.github.romanvht.byedpi.services.QuickTileService
import io.github.romanvht.byedpi.utility.RuntimeNotification
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.preference.PreferenceManager

class TgWsProxyService : Service() {

    private var statsJob: Job? = null
    private var healthJob: Job? = null
    private var restartJob: Job? = null
    private var lastNotificationContent: String = ""
    private var lastNotificationAtMs: Long = 0L
    private var notificationStartedAtMs: Long = 0L
    @Volatile
    private var stopInProgress = false
    @Volatile
    private var autoRestartAttempts = 0
    @Volatile
    private var healthRestarts = 0
    @Volatile
    private var lastTcpFallbackStat = 0L
    private var networkRegisteredAtMs = 0L
    private var lastNetworkRestartMs = 0L
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val stopScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Saved params for restart on kill / onTaskRemoved / auto-restart
    private var lastBindIp: String = "127.0.0.1"
    private var lastPort: Int = 1443
    private var lastIps: String = ""
    private var lastPoolSize: Int = 4
    private var lastBufferSizeKb: Int = 256
    private var lastCfEnabled: Boolean = true
    private var lastCfPriority: Boolean = true
    private var lastCfDomain: String = ""
    private var lastCfH2Enabled: Boolean = true
    private var lastCfWorkerDomains: String = ""
    private var lastFakeTlsDomain: String = ""
    private var lastDisableSecure: Boolean = false
    private var lastTestDc: Boolean = false
    private var lastProxyProtocol: Boolean = false
    private var lastVerbose: Boolean = false
    private var lastSecretKey: String = ""

    companion object {
        const val ACTION_START = "io.github.romanvht.byedpi.tgws.START"
        const val ACTION_STOP = "io.github.romanvht.byedpi.tgws.STOP"
        const val ACTION_RESTART = "io.github.romanvht.byedpi.tgws.RESTART"
        const val EXTRA_BIND_IP = "EXTRA_BIND_IP"
        const val EXTRA_PORT = "EXTRA_PORT"
        const val EXTRA_IPS = "EXTRA_IPS"
        const val EXTRA_POOL_SIZE = "EXTRA_POOL_SIZE"
        const val EXTRA_BUFFER_SIZE_KB = "EXTRA_BUFFER_SIZE_KB"
        const val EXTRA_CFPROXY_ENABLED = "EXTRA_CFPROXY_ENABLED"
        const val EXTRA_CFPROXY_PRIORITY = "EXTRA_CFPROXY_PRIORITY"
        const val EXTRA_CFPROXY_DOMAIN = "EXTRA_CFPROXY_DOMAIN"
        const val EXTRA_CFPROXY_H2 = "EXTRA_CFPROXY_H2"
        const val EXTRA_CF_WORKER_DOMAINS = "EXTRA_CF_WORKER_DOMAINS"
        const val EXTRA_FAKETLS_DOMAIN = "EXTRA_FAKETLS_DOMAIN"
        const val EXTRA_DISABLE_SECURE = "EXTRA_DISABLE_SECURE"
        const val EXTRA_TEST_DC = "EXTRA_TEST_DC"
        const val EXTRA_PROXY_PROTOCOL = "EXTRA_PROXY_PROTOCOL"
        const val EXTRA_VERBOSE = "EXTRA_VERBOSE"
        const val EXTRA_SECRET_KEY = "EXTRA_SECRET_KEY"

        private const val CHANNEL_ID = "TG_WS_Proxy_Service_v4"
        private const val TAG = "ProxyService"

        private const val STATS_UPDATE_MS = 10_000L
        private const val NOTIFICATION_MIN_UPDATE_MS = 10_000L

        /**
         * Пауза после NativeProxy.stopProxy(). Натив останавливается синхронно
         * и сам ждёт завершения задач, поэтому долгое ожидание здесь только
         * растягивало выключение: раньше прокси «гас» ощутимо медленно.
         */
        private const val NATIVE_STOP_WAIT_MS = 150L

        /** Пауза между остановкой и повторным подъёмом ядра при перезапуске. */
        private const val RESTART_SETTLE_MS = 250L

        private const val MAX_AUTO_RESTARTS = 3
        private const val AUTO_RESTART_DELAY_MS = 2_000L
        private const val MAX_HEALTH_FAILURES = 3
        private const val MAX_HEALTH_RESTARTS = 6
        private const val NETWORK_IGNORE_MS = 15_000L
        private const val NETWORK_DEBOUNCE_MS = 30_000L
        private const val HEARTBEAT_MS = 60_000L
        private const val HEALTH_CHECK_MS = 5_000L
        private const val PORT_PROBE_TIMEOUT_MS = 1500

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning
        private val _isVerifiedRunning = MutableStateFlow(false)
        val isVerifiedRunning: StateFlow<Boolean> = _isVerifiedRunning

        private val _lastTraffic = MutableStateFlow<String?>(null)
        val lastTraffic: StateFlow<String?> = _lastTraffic
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        LogManager.app(
            "service: onCreate | " + io.github.romanvht.byedpi.BuildConfig.VERSION_NAME +
                " (" + io.github.romanvht.byedpi.BuildConfig.VERSION_CODE + ") | " +
                io.github.romanvht.byedpi.BuildConfig.BUILD_STAMP
        )
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                autoRestartAttempts = 0
                healthRestarts = 0
                if (!LogManager.isListening()) LogManager.startListening(this)
                LogManager.app("START: запрос запуска прокси")
                val bindIp = intent.getStringExtra(EXTRA_BIND_IP) ?: "127.0.0.1"
                val port = intent.getIntExtra(EXTRA_PORT, 1443)
                val ips = intent.getStringExtra(EXTRA_IPS) ?: ""
                val poolSize = intent.getIntExtra(EXTRA_POOL_SIZE, 4)
                val bufferSizeKb = intent.getIntExtra(EXTRA_BUFFER_SIZE_KB, 256)
                val cfEnabled = intent.getBooleanExtra(EXTRA_CFPROXY_ENABLED, true)
                val cfPriority = intent.getBooleanExtra(EXTRA_CFPROXY_PRIORITY, true)
                val cfDomain = intent.getStringExtra(EXTRA_CFPROXY_DOMAIN) ?: ""
                val cfH2 = intent.getBooleanExtra(EXTRA_CFPROXY_H2, true)
                val cfWorkerDomains = intent.getStringExtra(EXTRA_CF_WORKER_DOMAINS) ?: ""
                val fakeTlsDomain = intent.getStringExtra(EXTRA_FAKETLS_DOMAIN) ?: ""
                val disableSecure = intent.getBooleanExtra(EXTRA_DISABLE_SECURE, EwenloyTgWsServiceExtension.DEF_DISABLE_SECURE)
                val testDc = intent.getBooleanExtra(EXTRA_TEST_DC, false)
                val proxyProtocol = intent.getBooleanExtra(EXTRA_PROXY_PROTOCOL, false)
                val verbose = intent.getBooleanExtra(EXTRA_VERBOSE, false)
                val secretKey = intent.getStringExtra(EXTRA_SECRET_KEY) ?: ""
                if (_isRunning.value && !stopInProgress) {
                    // Прокси уже работает, а пришёл новый конфиг (человек поменял
                    // настройку). Применяем на месте: без stopSelf и без потери
                    // 3-секундного ожидания — раньше старт после смены настроек
                    // ощущался как «не запускается».
                    LogManager.app("START: применяю новые настройки на работающем ядре")
                    applyConfigRestart(bindIp, port, ips, poolSize, bufferSizeKb,
                        cfEnabled, cfPriority, cfDomain, cfH2,
                        cfWorkerDomains, fakeTlsDomain, disableSecure,
                        testDc, proxyProtocol, verbose, secretKey)
                } else {
                    startProxy(bindIp, port, ips, poolSize, bufferSizeKb,
                        cfEnabled, cfPriority, cfDomain, cfH2,
                        cfWorkerDomains, fakeTlsDomain, disableSecure,
                        testDc, proxyProtocol, verbose, secretKey)
                }
            }
            ACTION_STOP -> { stopProxy() }
            ACTION_RESTART -> { restartProxy() }
            null -> {
                if (lastPort > 0 && lastSecretKey.isNotEmpty()) {
                    Log.w(TAG, "Service restarted by system, re-starting proxy")
                    startProxy(lastBindIp, lastPort, lastIps, lastPoolSize, lastBufferSizeKb,
                        lastCfEnabled, lastCfPriority, lastCfDomain, lastCfH2Enabled,
                        lastCfWorkerDomains, lastFakeTlsDomain, lastDisableSecure,
                        lastTestDc, lastProxyProtocol, lastVerbose, lastSecretKey)
                } else {
                    stopSelf()
                }
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun isPortAvailable(bindIp: String, port: Int): Boolean {
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getByName(bindIp), port))
                true
            }
        } catch (_: Exception) { false }
    }

    private fun startProxy(
        bindIp: String, port: Int, ips: String,
        poolSize: Int, bufferSizeKb: Int,
        cfEnabled: Boolean, cfPriority: Boolean, cfDomain: String, cfH2Enabled: Boolean,
        cfWorkerDomains: String, fakeTlsDomain: String, disableSecure: Boolean,
        testDc: Boolean, proxyProtocol: Boolean, verbose: Boolean,
        secretKey: String
    ) {
        if (_isRunning.value || stopInProgress) return
        _isVerifiedRunning.value = false

        lastBindIp = bindIp; lastPort = port; lastIps = ips
        lastPoolSize = poolSize; lastBufferSizeKb = bufferSizeKb
        lastCfEnabled = cfEnabled; lastCfPriority = cfPriority; lastCfDomain = cfDomain
        lastCfH2Enabled = cfH2Enabled; lastCfWorkerDomains = cfWorkerDomains
        lastFakeTlsDomain = fakeTlsDomain; lastDisableSecure = disableSecure
        lastTestDc = testDc; lastProxyProtocol = proxyProtocol; lastVerbose = verbose
        lastSecretKey = secretKey
        notificationStartedAtMs = System.currentTimeMillis()
        lastNotificationContent = getString(R.string.notification_starting)
        lastNotificationAtMs = notificationStartedAtMs
        _lastTraffic.value = null

        showNotification(lastNotificationContent)
        registerNetworkCallback()
        LogManager.app("start: поднимаю ядро на $bindIp:$port (pool=$poolSize, cf=$cfEnabled, tls=${!disableSecure})")
        startHealthJob()
        stopInProgress = false

        Thread({
            if (!isPortAvailable(bindIp, port)) {
                val str = getString(R.string.port_is_not_available, port)
                serviceScope.launch(Dispatchers.Main) {
                    Toast.makeText(this@TgWsProxyService, str, Toast.LENGTH_SHORT).show()
                    updateNotification(str, force = true)
                    stopProxy()
                }
                return@Thread
            }
            try {
                NativeProxy.setPoolSize(poolSize)
                NativeProxy.setBufferSizeKb(bufferSizeKb)
                NativeProxy.setCfProxyCacheDir(cacheDir.absolutePath)
                NativeProxy.setCfProxyConfig(cfEnabled, cfPriority, cfDomain)
                NativeProxy.setCfWorkerDomains(cfWorkerDomains)
                NativeProxy.setFakeTlsDomain(fakeTlsDomain)
                NativeProxy.setDisableSecure(disableSecure)
                NativeProxy.setForceTestDc(testDc)
                NativeProxy.setProxyProtocol(proxyProtocol)
                val result = NativeProxy.startProxy(bindIp, port, ips, secretKey, if (verbose) 1 else 0)
                if (result == 0) {
                    autoRestartAttempts = 0
                    serviceScope.launch {
                        if (!stopInProgress) {
                            updateRunningState(true)
                            _isVerifiedRunning.value = true
                            PreferenceManager.getDefaultSharedPreferences(this@TgWsProxyService).edit()
                                .putString(EwenloyTgWsServiceExtension.EWENLOY_TG_RUNTIME_STATUS_KEY, EwenloyTgWsServiceExtension.TG_STATUS_IDLE)
                                .apply()
                            updateNotification(getString(R.string.notification_running), force = true)
                            Log.i(TAG, "Proxy ready: listening on port $port")
                            LogManager.app("start: ядро готово, слушает $port")
                        }
                    }
                } else {
                    Log.e(TAG, "StartProxy returned error code: $result")
                    LogManager.app("start: ошибка старта, код $result", error = true)
                    scheduleAutoRestartOrStop(getString(R.string.notification_start_error_code, result))
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to start proxy via JNA", e)
                LogManager.app("start: исключение ${e.message}", error = true)
                scheduleAutoRestartOrStop(getString(R.string.notification_error, e.message ?: ""))
            }
        }, "ProxyStart").apply { isDaemon = true; start() }

        // Старый сборщик статистики мог остаться от предыдущего запуска:
        // без отмены после рестарта их два, и уведомление мигает вдвое чаще.
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            delay(3_000L)
            var consecutiveHealthFailures = 0
            while (isActive) {
                delay(STATS_UPDATE_MS)
                if (!_isRunning.value || stopInProgress) { consecutiveHealthFailures = 0; continue }
                try {
                    val rawStats = NativeProxy.getStats()
                    if (rawStats == null) {
                        consecutiveHealthFailures++
                        if (consecutiveHealthFailures >= MAX_HEALTH_FAILURES) {
                            consecutiveHealthFailures = 0
                            scheduleAutoRestartOrStop(getString(R.string.notification_error, "proxy not responding"))
                        }
                        continue
                    }
                    consecutiveHealthFailures = 0
                    val upRaw = extractStat(rawStats, "up=")
                    val downRaw = extractStat(rawStats, "down=")
                    val activeConns = extractStat(rawStats, "active=")
                    val totalBytes = parseHumanBytes(upRaw) + parseHumanBytes(downRaw)
                    val active = activeConns.toIntOrNull() ?: 0
                    val text = getString(R.string.notification_traffic, formatBytes(totalBytes), active)
                    _lastTraffic.value = text
                    updateNotification(text)
                    updateTgWsRuntimeStatus(rawStats)
                } catch (e: Exception) { Log.w(TAG, "Stats update failed", e) }
            }
        }
    }

    private fun updateNotification(content: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force) {
            if (content == lastNotificationContent) return
            if (lastNotificationAtMs != 0L && now - lastNotificationAtMs < NOTIFICATION_MIN_UPDATE_MS) return
        }
        lastNotificationContent = content
        lastNotificationAtMs = now
        showNotification(content)
    }

    private fun showNotification(content: String) {
        try {
            val notification = RuntimeNotification.build(this, RuntimeNotification.runningMode(), content)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(RuntimeNotification.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(RuntimeNotification.ID, notification)
            }
        } catch (e: Exception) { Log.w(TAG, "Failed to update notification", e) }
    }

    private fun scheduleAutoRestartOrStop(errorText: String) {
        serviceScope.launch {
            updateNotification(errorText, force = true)
            if (autoRestartAttempts < MAX_AUTO_RESTARTS && lastPort > 0 && lastSecretKey.isNotEmpty()) {
                autoRestartAttempts++
                Log.w(TAG, "Native proxy failed, auto-restart $autoRestartAttempts/$MAX_AUTO_RESTARTS")
                LogManager.app("auto-restart: попытка $autoRestartAttempts/$MAX_AUTO_RESTARTS")
                delay(AUTO_RESTART_DELAY_MS.toLong())
                if (stopInProgress) return@launch
                restartProxy()
            } else {
                Log.e(TAG, "Auto-restart limit reached, stopping")
                LogManager.app("auto-restart: лимит исчерпан, остановка", error = true)
                stopProxy()
            }
        }
    }

    private fun stopProxy() {
        if (stopInProgress) return
        stopInProgress = true
        // Гасим состояние сразу, не дожидаясь натива: иначе переключатель
        // и уведомление висят «включёнными» ещё несколько секунд.
        updateRunningState(false)
        LogManager.stopListening()
        LogManager.app("stop: пользователь/сервис остановил прокси")
        updateNotification(getString(R.string.notification_stopping), force = true)

        stopScope.launch {
            try {
                NativeProxy.stopProxy()
                delay(NATIVE_STOP_WAIT_MS)
            } catch (e: Exception) {
                Log.w(TAG, "Exception during proxy stop", e)
            }
            val nMgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nMgr.cancel(RuntimeNotification.ID)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Применить новые настройки к уже работающему ядру.
     *
     * Быстрее полного stop+start: состояние гасим сразу, натив останавливаем
     * синхронно и поднимаем заново с новым конфигом, не останавливая сервис и
     * не пересоздавая уведомление с нуля.
     */
    private fun applyConfigRestart(
        bindIp: String, port: Int, ips: String,
        poolSize: Int, bufferSizeKb: Int,
        cfEnabled: Boolean, cfPriority: Boolean, cfDomain: String, cfH2Enabled: Boolean,
        cfWorkerDomains: String, fakeTlsDomain: String, disableSecure: Boolean,
        testDc: Boolean, proxyProtocol: Boolean, verbose: Boolean,
        secretKey: String
    ) {
        if (restartJob?.isActive == true) return
        restartJob = serviceScope.launch {
            statsJob?.cancel(); statsJob = null
            updateRunningState(false)
            try {
                NativeProxy.stopProxy()
            } catch (e: Exception) {
                Log.w(TAG, "Config restart: stop exception", e)
            }
            delay(RESTART_SETTLE_MS)
            if (!stopInProgress) {
                startProxy(bindIp, port, ips, poolSize, bufferSizeKb,
                    cfEnabled, cfPriority, cfDomain, cfH2Enabled,
                    cfWorkerDomains, fakeTlsDomain, disableSecure,
                    testDc, proxyProtocol, verbose, secretKey)
            }
        }
    }

    private fun restartProxy() {
        if (stopInProgress || lastPort <= 0 || lastSecretKey.isEmpty()) return
        // Один перезапуск за раз: health-check, смена сети и task removed
        // приходят пачками, и параллельные рестарты рвут уже поднятое ядро.
        if (restartJob?.isActive == true) return

        restartJob = serviceScope.launch {
            LogManager.app("restart: перезапуск ядра")
            statsJob?.cancel(); statsJob = null
            try {
                NativeProxy.stopProxy()
            } catch (e: Exception) {
                Log.w(TAG, "Restart: stopProxy exception", e)
            }
            // Критично: сбросить флаг «работает» ДО запуска. Иначе startProxy()
            // выйдет по guard-условию `if (_isRunning.value ...) return`, порт
            // 1082 останется мёртвым, а health-check будет крутить перезапуски
            // по кругу. Именно так прокси умирал после смахивания из недавних.
            updateRunningState(false)
            delay(RESTART_SETTLE_MS)
            if (!stopInProgress) {
                startProxy(
                    lastBindIp, lastPort, lastIps, lastPoolSize, lastBufferSizeKb,
                    lastCfEnabled, lastCfPriority, lastCfDomain, lastCfH2Enabled,
                    lastCfWorkerDomains, lastFakeTlsDomain, lastDisableSecure,
                    lastTestDc, lastProxyProtocol, lastVerbose, lastSecretKey
                )
            }
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - networkRegisteredAtMs < NETWORK_IGNORE_MS) return
                    if (now - lastNetworkRestartMs < NETWORK_DEBOUNCE_MS) return
                    if (!_isRunning.value || stopInProgress) return
                    lastNetworkRestartMs = now
                    LogManager.app("сеть изменилась: перезапуск ядра для сброса кулдаунов")
                    restartProxy()
                }
            }
            networkRegisteredAtMs = SystemClock.elapsedRealtime()
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
        } catch (e: Exception) {
            LogManager.app("сеть: не удалось подписаться на события (${e.message})", error = true)
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            val cb = networkCallback ?: return
            val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            cm.unregisterNetworkCallback(cb)
            networkCallback = null
        } catch (e: Exception) { Log.w(TAG, "Failed to unregister network callback", e) }
    }

    private fun startHealthJob() {
        healthJob?.cancel()
        healthJob = serviceScope.launch {
            var failures = 0
            var lastBeatMs = 0L
            while (isActive) {
                delay(HEALTH_CHECK_MS)
                if (!_isRunning.value || stopInProgress) { failures = 0; continue }
                if (!isProxyPortAlive(lastBindIp, lastPort)) {
                    failures++
                    LogManager.app("health: порт $lastPort не отвечает ($failures/$MAX_HEALTH_FAILURES)")
                    if (failures >= MAX_HEALTH_FAILURES) {
                        failures = 0
                        healthRestarts++
                        if (healthRestarts <= MAX_HEALTH_RESTARTS) {
                            LogManager.app("health: ядро мертво -> перезапуск $healthRestarts/$MAX_HEALTH_RESTARTS", error = true)
                            restartProxy()
                        } else {
                            LogManager.app("health: лимит перезапусков исчерпан, останавливаю прокси", error = true)
                            updateNotification(getString(R.string.notification_error, "proxy is dead"), force = true)
                            stopProxy()
                        }
                    }
                    continue
                }
                failures = 0
                val now = System.currentTimeMillis()
                if (now - lastBeatMs >= HEARTBEAT_MS) {
                    lastBeatMs = now
                    val stats = try { NativeProxy.getStats() } catch (_: Throwable) { null }
                    LogManager.app("beat: ${stats ?: "статистики нет"}")
                }
            }
        }
    }

    private fun isProxyPortAlive(bindIp: String, port: Int): Boolean {
        if (port <= 0) return false
        val host = when (bindIp) { "0.0.0.0" -> "127.0.0.1"; "::", "[::]" -> "::1"; else -> bindIp }
        return try {
            java.net.Socket().use { s -> s.connect(java.net.InetSocketAddress(host, port), PORT_PROBE_TIMEOUT_MS); true }
        } catch (_: Exception) { false }
    }

    private fun updateTgWsRuntimeStatus(rawStats: String) {
        val m = java.util.regex.Pattern.compile("(ws|direct|tcp_fallback|cfproxy)=").matcher(rawStats)
        val ws = m.find() && m.group(1) == "ws"
        val direct = m.find() && m.group(1) == "direct"
        val tcpFb = m.find() && m.group(1) == "tcp_fallback"
        val cf = m.find() && m.group(1) == "cfproxy"
        val status = when {
            ws && (cf || direct || tcpFb) -> EwenloyTgWsServiceExtension.TG_STATUS_WS
            direct || tcpFb || cf -> EwenloyTgWsServiceExtension.TG_STATUS_DIRECT
            else -> EwenloyTgWsServiceExtension.TG_STATUS_IDLE
        }
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString(EwenloyTgWsServiceExtension.EWENLOY_TG_RUNTIME_STATUS_KEY, status)
            .putString(EwenloyTgWsServiceExtension.EWENLOY_TG_DIAGNOSTICS_KEY, rawStats)
            .apply()
    }

    private fun updateRunningState(running: Boolean) {
        val wasRunning = _isRunning.value
        _isRunning.value = running
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) QuickTileService.updateTile()
    }

    private fun extractStat(stats: String, key: String): String {
        val idx = stats.indexOf(key)
        if (idx < 0) return "0"
        val end = stats.indexOfAny(charArrayOf(' ', '\n', '\t', ','), idx + key.length).let { if (it < 0) stats.length else it }
        return stats.substring(idx + key.length, end)
    }

    private fun parseHumanBytes(s: String): Long {
        val m = java.util.regex.Pattern.compile("([0-9.]+)([KMGT]?)").matcher(s.trim())
        if (!m.matches()) return 0L
        val num = m.group(1).toDoubleOrNull() ?: 0.0
        return when (m.group(2)) {
            "K" -> (num * 1024).toLong(); "M" -> (num * 1024 * 1024).toLong()
            "G" -> (num * 1024 * 1024 * 1024).toLong(); "T" -> (num * 1024L * 1024 * 1024 * 1024).toLong()
            else -> num.toLong()
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        if (bytes < 1024 * 1024) return "%.1fKB".format(bytes / 1024.0)
        if (bytes < 1024L * 1024 * 1024) return "%.1fMB".format(bytes / (1024.0 * 1024))
        return "%.1fGB".format(bytes / (1024.0 * 1024 * 1024))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false); setSound(null, null); enableVibration(false); enableLights(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        LogManager.app("task removed")
        if (lastPort > 0 && lastSecretKey.isNotEmpty() && _isRunning.value && !stopInProgress) {
            LogManager.app("перезапуск после task removed")
            restartProxy()
        }
    }

    override fun onDestroy() {
        LogManager.app("service: onDestroy (running=${_isRunning.value})")
        restartJob?.cancel(); restartJob = null
        statsJob?.cancel(); statsJob = null
        healthJob?.cancel(); healthJob = null
        unregisterNetworkCallback()
        if (_isRunning.value) {
            updateRunningState(false)
            PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString(EwenloyTgWsServiceExtension.EWENLOY_TG_RUNTIME_STATUS_KEY, EwenloyTgWsServiceExtension.TG_STATUS_DISABLED)
                .apply()
        }
        serviceScope.cancel()
        LogManager.stopListening()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
