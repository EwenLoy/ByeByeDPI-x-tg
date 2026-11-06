package io.github.romanvht.byedpi.ewenloy.tgws

import android.app.Notification
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
import androidx.core.app.NotificationCompat
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
import android.content.SharedPreferences
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
    // Счётчик подряд неудачных стартов натива для авто-рестарта
    @Volatile
    private var autoRestartAttempts = 0
    // Health-check: сколько раз ядро не отвечало на порту и сколько было перезапусков
    @Volatile
    private var healthRestarts = 0
    @Volatile
    private var lastTcpFallbackStat = 0L
    private var networkRegisteredAtMs = 0L
    private var lastNetworkRestartMs = 0L
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Отдельный scope для остановки: НЕ отменяется в onDestroy(),
    // иначе stopForeground()/stopSelf() не выполнятся и уведомление зависнет
    private val stopScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    // Saved intent extras for restart on kill / onTaskRemoved
    private var lastBindIp: String = "127.0.0.1"
    private var lastPort: Int = 1443
    private var lastIps: String = ""
    private var lastPoolSize: Int = 4
    private var lastCfEnabled: Boolean = true
    private var lastCfPriority: Boolean = true
    private var lastCfDomain: String = ""
    private var lastSecretKey: String = ""

    companion object {
        const val ACTION_START = "io.github.romanvht.byedpi.tgws.START"
        const val ACTION_STOP = "io.github.romanvht.byedpi.tgws.STOP"
        const val ACTION_RESTART = "io.github.romanvht.byedpi.tgws.RESTART"
        const val EXTRA_BIND_IP = "EXTRA_BIND_IP"
        const val EXTRA_PORT = "EXTRA_PORT"
        const val EXTRA_IPS = "EXTRA_IPS"
        const val EXTRA_POOL_SIZE = "EXTRA_POOL_SIZE"
        const val EXTRA_CFPROXY_ENABLED = "EXTRA_CFPROXY_ENABLED"
        const val EXTRA_CFPROXY_PRIORITY = "EXTRA_CFPROXY_PRIORITY"
        const val EXTRA_CFPROXY_DOMAIN = "EXTRA_CFPROXY_DOMAIN"
        const val EXTRA_SECRET_KEY = "EXTRA_SECRET_KEY"
        
        private const val CHANNEL_ID = "TG_WS_Proxy_Service_v4"
        private const val TAG = "ProxyService"

        // Stats/notification update interval.
        private const val STATS_UPDATE_MS = 10_000L
        private const val NOTIFICATION_MIN_UPDATE_MS = 10_000L
        private const val NATIVE_STOP_WAIT_MS = 3_000L

        // Авто-рестарт натива при неожиданном падении/неудачном старте
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

        // Последняя строка трафика ("Трафик: 1.2MB · 3 сесс.") для сводки в уведомлении ByeDPI
        private val _lastTraffic = MutableStateFlow<String?>(null)
        val lastTraffic: StateFlow<String?> = _lastTraffic
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        LogManager.startListening(this)
        LogManager.app("service: onCreate")
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                autoRestartAttempts = 0
                healthRestarts = 0
                LogManager.startListening(this)
                LogManager.app("START: запрос запуска прокси")
                val bindIp = intent.getStringExtra(EXTRA_BIND_IP) ?: "127.0.0.1"
                val port = intent.getIntExtra(EXTRA_PORT, 1443)
                val ips = intent.getStringExtra(EXTRA_IPS) ?: ""
                val poolSize = intent.getIntExtra(EXTRA_POOL_SIZE, 4)
                val cfEnabled = intent.getBooleanExtra(EXTRA_CFPROXY_ENABLED, true)
                val cfPriority = intent.getBooleanExtra(EXTRA_CFPROXY_PRIORITY, true)
                val cfDomain = intent.getStringExtra(EXTRA_CFPROXY_DOMAIN) ?: ""
                val secretKey = intent.getStringExtra(EXTRA_SECRET_KEY) ?: ""
                startProxy(bindIp, port, ips, poolSize, cfEnabled, cfPriority, cfDomain, secretKey)
            }
            ACTION_STOP -> {
                stopProxy()
            }
            ACTION_RESTART -> {
                restartProxy()
            }
            null -> {
                // Service restarted by system after being killed (START_REDELIVER_INTENT)
                // If we had saved params, try to restart
                if (lastPort > 0 && lastSecretKey.isNotEmpty()) {
                    Log.w(TAG, "Service restarted by system, re-starting proxy")
                    startProxy(lastBindIp, lastPort, lastIps, lastPoolSize, lastCfEnabled, lastCfPriority, lastCfDomain, lastSecretKey)
                } else {
                    stopSelf()
                }
            }
        }
        // START_REDELIVER_INTENT: if the system kills the service, it will restart it
        // and re-deliver the last intent, so we don't lose the config.
        return START_REDELIVER_INTENT
    }

    private fun isPortAvailable(bindIp: String, port: Int): Boolean {
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getByName(bindIp), port))
                true
            }
        } catch (_: Exception) {
            // (Если надо, то тут может быть IOException или SecurityException) (IOException добавляет новый import так что "_", по-моему, лучше)
            return false;
        }
    }


    private fun startProxy(bindIp: String, port: Int, ips: String, poolSize: Int = 4,
                           cfEnabled: Boolean = true, cfPriority: Boolean = true,
                           cfDomain: String = "", secretKey: String = "") {
        if (_isRunning.value || stopInProgress) return
        _isVerifiedRunning.value = false

        // Save params for restart
        lastBindIp = bindIp
        lastPort = port
        lastIps = ips
        lastPoolSize = poolSize
        lastCfEnabled = cfEnabled
        lastCfPriority = cfPriority
        lastCfDomain = cfDomain
        lastSecretKey = secretKey
        notificationStartedAtMs = System.currentTimeMillis()
        lastNotificationContent = getString(R.string.notification_starting)
        lastNotificationAtMs = notificationStartedAtMs
        _lastTraffic.value = null

        showNotification(lastNotificationContent)
        registerNetworkCallback()
        LogManager.app("start: поднимаю ядро на $bindIp:$port (pool=$poolSize, cf=$cfEnabled)")
        startHealthJob()

        stopInProgress = false
        
        // Start Go proxy in a separate thread with error handling
        Thread({
            val canObtainPort = isPortAvailable(bindIp, port)
            if (!canObtainPort) {
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
                NativeProxy.setCfProxyCacheDir(cacheDir.absolutePath)
                NativeProxy.setCfProxyConfig(cfEnabled, cfPriority, cfDomain)
                val result = NativeProxy.startProxy(bindIp, port, ips, secretKey, 1)
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
                    scheduleAutoRestartOrStop(
                        getString(R.string.notification_start_error_code, result)
                    )
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to start proxy via JNA", e)
                LogManager.app("start: исключение ${e.message}", error = true)
                scheduleAutoRestartOrStop(
                    getString(R.string.notification_error, e.message ?: "")
                )
            }
        }, "ProxyStart").apply {
            isDaemon = true
            start()
        }

                    // Don't stop — it might start slightly later; let the user decide
        // Stats updater. Notification updates are throttled so the system keeps
        // a stable foreground entry instead of constantly reordering it.
        statsJob = serviceScope.launch {
            // Начальная задержка: даём нативу 3 секунды на разогрев пула
            delay(3_000L)
            var consecutiveHealthFailures = 0
            while (isActive) {
                delay(STATS_UPDATE_MS)
                if (_isRunning.value && !stopInProgress) {
                    try {
                        val rawStats = NativeProxy.getStats()
                        if (rawStats == null) {
                            consecutiveHealthFailures++
                            Log.w(TAG, "Health check: getStats()=null ($consecutiveHealthFailures/$MAX_HEALTH_FAILURES)")
                            if (consecutiveHealthFailures >= MAX_HEALTH_FAILURES) {
                                Log.e(TAG, "Native proxy dead (getStats null ${MAX_HEALTH_FAILURES}x), restarting")
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

                        // Обновляем реальный статус TG WS для UI
                        updateTgWsRuntimeStatus(rawStats)
                    } catch (e: Exception) {
                        Log.w(TAG, "Stats update failed", e)
                    }
                }
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

    /** Одна карточка на всё приложение: startForeground заодно обновляет её текст. */
    private fun showNotification(content: String) {
        try {
            val notification = RuntimeNotification.build(this, RuntimeNotification.runningMode(), content)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    RuntimeNotification.ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(RuntimeNotification.ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification", e)
        }
    }

    /**
     * Натив не смог подняться (порт занят, кратковременный сбой, крэш).
     * Вместо того чтобы ронять сервис — сами перезапускаем прокси с сохранёнными
     * параметрами до MAX_AUTO_RESTARTS раз с паузой AUTO_RESTART_DELAY_MS.
     * После исчерпания попыток — штатная остановка.
     */
    private fun scheduleAutoRestartOrStop(errorText: String) {
        serviceScope.launch {
            updateNotification(errorText, force = true)

            if (autoRestartAttempts < MAX_AUTO_RESTARTS && lastPort > 0 && lastSecretKey.isNotEmpty()) {
                autoRestartAttempts++
                Log.w(TAG, "Native proxy failed, auto-restart ${autoRestartAttempts}/$MAX_AUTO_RESTARTS")
                delay(AUTO_RESTART_DELAY_MS)
                if (!stopInProgress) {
                    startProxy(
                        bindIp = lastBindIp,
                        port = lastPort,
                        ips = lastIps,
                        poolSize = lastPoolSize,
                        cfEnabled = lastCfEnabled,
                        cfPriority = lastCfPriority,
                        cfDomain = lastCfDomain,
                        secretKey = lastSecretKey
                    )
                }
            } else {
                Log.e(TAG, "Auto-restart gave up, stopping service")
                delay(3000)
                stopProxy()
            }
        }
    }

    private fun restartProxy() {
        if (restartJob?.isActive == true) return
        if (lastPort <= 0 || lastSecretKey.isEmpty()) {
            Log.w(TAG, "Restart requested without saved proxy configuration")
            return
        }

        restartJob = serviceScope.launch {
            Log.i(TAG, "Restarting proxy from notification")
            updateNotification(getString(R.string.notification_restarting), force = true)

            statsJob?.cancel()
            statsJob = null

            requestNativeStop("restart")
            updateRunningState(false)
            PreferenceManager.getDefaultSharedPreferences(this@TgWsProxyService).edit()
                .putString(EwenloyTgWsServiceExtension.EWENLOY_TG_RUNTIME_STATUS_KEY, EwenloyTgWsServiceExtension.TG_STATUS_DISABLED)
                .apply()
            delay(350)

            startProxy(
                bindIp = lastBindIp,
                port = lastPort,
                ips = lastIps,
                poolSize = lastPoolSize,
                cfEnabled = lastCfEnabled,
                cfPriority = lastCfPriority,
                cfDomain = lastCfDomain,
                secretKey = lastSecretKey
            )
        }
    }

    private fun extractStat(stats: String, key: String): String {
        val idx = stats.indexOf(key)
        if (idx == -1) return "0B"
        val start = idx + key.length
        val end = stats.indexOf(" ", start)
        return if (end == -1) stats.substring(start) else stats.substring(start, end)
    }
    
    private fun parseHumanBytes(s: String): Double {
        val num = s.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0
        return when {
            s.endsWith("TB") -> num * 1024.0 * 1024 * 1024 * 1024
            s.endsWith("GB") -> num * 1024.0 * 1024 * 1024
            s.endsWith("MB") -> num * 1024.0 * 1024
            s.endsWith("KB") -> num * 1024.0
            else -> num
        }
    }
    
    private fun formatBytes(bytes: Double): String {
        if (bytes < 1024) return "%.0fB".format(bytes)
        if (bytes < 1024 * 1024) return "%.1fKB".format(bytes / 1024)
        if (bytes < 1024 * 1024 * 1024) return "%.1fMB".format(bytes / (1024 * 1024))
        return "%.2fGB".format(bytes / (1024 * 1024 * 1024))
    }

    /**
     * Обновляет реальный статус TG WS прокси для UI.
     * Важно: ws=/cf=/tcp_fb= в GetStats() — НАКОПИТЕЛЬНЫЕ счётчики за всю сессию,
     * поэтому «живость» определяем по active, а direct — по приросту tcp_fb.
     */
    private fun updateTgWsRuntimeStatus(rawStats: String) {
        val active = extractStat(rawStats, "active=").toIntOrNull() ?: 0
        val tcpFb = extractStat(rawStats, "tcp_fb=").toLongOrNull() ?: 0L
        val tcpFallbackGrew = tcpFb > lastTcpFallbackStat
        lastTcpFallbackStat = tcpFb

        val status = when {
            active <= 0 -> EwenloyTgWsServiceExtension.TG_STATUS_IDLE
            tcpFallbackGrew -> EwenloyTgWsServiceExtension.TG_STATUS_DIRECT
            else -> EwenloyTgWsServiceExtension.TG_STATUS_WS
        }
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putString(EwenloyTgWsServiceExtension.EWENLOY_TG_RUNTIME_STATUS_KEY, status)
            .apply()
    }

    /**
     * Регистрируется ОДИН раз на процесс сервиса: повторная регистрация на каждый
     * startProxy давала накопление колбэков и лавину перезапусков (CM сразу
     * присылает onAvailable для уже активной сети).
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val now = SystemClock.elapsedRealtime()
                    // Стартовый колбэк (сеть уже есть) и дребезг Wi-Fi/LTE игнорируем
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
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister network callback", e)
        }
    }

    /**
     * Дешёвый health-check: раз в 5 секунд пробуем TCP-коннект на порт прокси.
     * Нативная библиотека при падении рантайма не уведомляет Kotlin, поэтому
     * единственный надёжный признак жизни — открытый порт.
     * Раз в минуту пишем heartbeat в файл: по последней строке видно, когда процесс умер.
     */
    private fun startHealthJob() {
        healthJob?.cancel()
        healthJob = serviceScope.launch {
            var failures = 0
            var lastBeatMs = 0L
            while (isActive) {
                delay(HEALTH_CHECK_MS)
                if (!_isRunning.value || stopInProgress) {
                    failures = 0
                    continue
                }
                if (!isProxyPortAlive(lastBindIp, lastPort)) {
                    failures++
                    LogManager.app(
                        "health: порт $lastPort не отвечает ($failures/$MAX_HEALTH_FAILURES)"
                    )
                    if (failures >= MAX_HEALTH_FAILURES) {
                        failures = 0
                        healthRestarts++
                        if (healthRestarts <= MAX_HEALTH_RESTARTS) {
                            LogManager.app(
                                "health: ядро мертво -> перезапуск $healthRestarts/$MAX_HEALTH_RESTARTS",
                                error = true
                            )
                            restartProxy()
                        } else {
                            LogManager.app(
                                "health: лимит перезапусков исчерпан, останавливаю прокси",
                                error = true
                            )
                            updateNotification(
                                getString(R.string.notification_error, "proxy is dead"),
                                force = true
                            )
                            stopProxy()
                        }
                    }
                    continue
                }
                failures = 0
                val now = System.currentTimeMillis()
                if (now - lastBeatMs >= HEARTBEAT_MS) {
                    lastBeatMs = now
                    val stats = try {
                        NativeProxy.getStats()
                    } catch (t: Throwable) {
                        null
                    }
                    LogManager.app("beat: ${stats ?: "статистики нет"}")
                }
            }
        }
    }

    private fun isProxyPortAlive(bindIp: String, port: Int): Boolean {
        if (port <= 0) return false
        val host = when (bindIp) {
            "0.0.0.0" -> "127.0.0.1"
            "::", "[::]" -> "::1"
            else -> bindIp
        }
        return try {
            java.net.Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), PORT_PROBE_TIMEOUT_MS)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun stopProxy() {
        if (stopInProgress) return
        stopInProgress = true
        LogManager.app("stop: пользователь/сервис остановил прокси")
        healthJob?.cancel()
        healthJob = null
        unregisterNetworkCallback()
        restartJob?.cancel()
        restartJob = null
        statsJob?.cancel()
        statsJob = null
        // Используем stopScope: он не отменяется в onDestroy(),
        // поэтому уведомление гарантированно убирается
        stopScope.launch {
            _lastTraffic.value = null
            // Состояние переключаем ДО остановки натива: иначе UI и шторка
            // остаются на тексте «Остановка прокси», пока сервис жив.
            updateRunningState(false)
            PreferenceManager.getDefaultSharedPreferences(this@TgWsProxyService).edit()
                .putString(EwenloyTgWsServiceExtension.EWENLOY_TG_RUNTIME_STATUS_KEY, EwenloyTgWsServiceExtension.TG_STATUS_DISABLED)
                .apply()
            requestNativeStop("stop")
            RuntimeNotification.sync(this@TgWsProxyService)
            // DETACH только если остался живой ByeDPI: карточка общая.
            // Если не осталось ничего — убираем её совсем, иначе висит «Остановка прокси».
            val nothingElseRunning = RuntimeNotification.runningMode() == null && !_isRunning.value
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(if (nothingElseRunning) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(nothingElseRunning)
            }
            RuntimeNotification.sync(this@TgWsProxyService)
            stopSelf()
        }
    }

    private suspend fun requestNativeStop(reason: String): Boolean {
        val completed = CompletableDeferred<Unit>()
        Thread({
            try {
                NativeProxy.stopProxy()
            } catch (t: Throwable) {
                // Throwable: UnsatisfiedLinkError и т.п. не должны вешать остановку
                Log.w(TAG, "StopProxy failed during $reason", t)
            } finally {
                completed.complete(Unit)
            }
        }, "ProxyStop-$reason").apply {
            isDaemon = true
            start()
        }

        val finished = withTimeoutOrNull(NATIVE_STOP_WAIT_MS) {
            completed.await()
            true
        } ?: false

        if (!finished) {
            Log.w(TAG, "Native stop is still running after ${NATIVE_STOP_WAIT_MS}ms during $reason")
        }
        return finished
    }

    /**
     * Called when the user swipes the app from recents.
     * Without this, the service would be killed on many OEM Androids.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (_isRunning.value) {
            Log.w(TAG, "onTaskRemoved: proxy is running, service stays alive")
            // The service continues because stopWithTask=false in manifest
            // No action needed — the service keeps running.
        }
    }

    private fun updateRunningState(isRunning: Boolean) {
        _isRunning.value = isRunning
        if (!isRunning) {
            _isVerifiedRunning.value = false
        }
        // Плитка быстрых настроек должна узнать о смене состояния сразу
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            QuickTileService.updateTile()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                // MIN: без иконки в статус-баре, внизу шторки — основная сводка
                // показывается в уведомлении ByeDPI
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }


    override fun onDestroy() {
        LogManager.app("service: onDestroy (running=${_isRunning.value})")
        restartJob?.cancel()
        restartJob = null
        statsJob?.cancel()
        statsJob = null
        healthJob?.cancel()
        healthJob = null
        unregisterNetworkCallback()
        if (_isRunning.value) {
            updateRunningState(false)
            PreferenceManager.getDefaultSharedPreferences(this@TgWsProxyService).edit()
                .putString(EwenloyTgWsServiceExtension.EWENLOY_TG_RUNTIME_STATUS_KEY, EwenloyTgWsServiceExtension.TG_STATUS_DISABLED)
                .apply()
        }
        // stopScope НЕ отменяем — стоп-последовательность должна завершиться
        serviceScope.cancel()
        LogManager.stopListening()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
