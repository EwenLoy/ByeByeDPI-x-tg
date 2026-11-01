package io.github.romanvht.byedpi.services

import android.app.Service
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import android.util.Log
import io.github.romanvht.byedpi.core.ByeDpiProxy
import io.github.romanvht.byedpi.core.TProxyService
import io.github.romanvht.byedpi.data.Configuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal class NativeSession(
    private val service: Service,
    private val createTunnel: ((Configuration) -> ParcelFileDescriptor)? = null,
) {
    companion object {
        private const val TAG = "NativeSession"
        private const val START_TIMEOUT = 10_000L
        private const val STOP_TIMEOUT = 2_000L
        private const val PROBE_TIMEOUT = 250
        private val processStarted = AtomicBoolean()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(service.mainLooper) { message ->
        handleMessage(message)
        true
    }
    private val messenger = Messenger(handler)
    private var client: Messenger? = null
    private var clientDeath: IBinder.DeathRecipient? = null
    private var configuration: Configuration? = null
    private var foregroundStarted = false
    private var stopOnClientDeath = false
    private var nativeStarted = false
    private var state = EngineProtocol.STATE_IDLE
    private var proxy: ByeDpiProxy? = null
    private var proxyThread: Thread? = null
    private var tunnelThread: Thread? = null
    private var tunFd: ParcelFileDescriptor? = null
    private var startJob: Job? = null

    val binder: IBinder get() = messenger.binder

    fun onBound(): Boolean {
        if (state == EngineProtocol.STATE_STOPPING || foregroundStarted ||
            configuration != null && !stopOnClientDeath) return false
        if (!stopOnClientDeath) {
            stopOnClientDeath = true
            handler.postDelayed({ if (configuration == null) fail("Engine configuration timed out") }, START_TIMEOUT)
        }
        return true
    }

    fun onUnbound() {
        if (stopOnClientDeath) stop()
    }

    fun onStarted() {
        if (state == EngineProtocol.STATE_STOPPING) return
        if (!foregroundStarted) {
            foregroundStarted = true
            handler.postDelayed({ if (configuration == null) stop() }, START_TIMEOUT)
        }
        startNative()
    }

    fun stop() {
        if (state == EngineProtocol.STATE_STOPPING) return
        if (!nativeStarted && processStarted.get()) {
            state = EngineProtocol.STATE_STOPPING
            service.stopSelf()
            return
        }
        state = EngineProtocol.STATE_STOPPING
        reply(EngineProtocol.STOPPING)
        handler.postDelayed({ terminate() }, STOP_TIMEOUT)
        startJob?.cancel()
        thread(name = "NativeEngineStop") {
            try {
                if (tunnelThread != null) TProxyService.stopTunnel()
                proxy?.stopProxy()
                tunnelThread?.join()
                proxyThread?.join()
                tunFd?.close()
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to stop engine gracefully", e)
            } finally {
                terminate()
            }
        }
    }

    fun destroy() {
        scope.cancel()
        handler.removeCallbacksAndMessages(null)
        detachClient()
        if (nativeStarted || !processStarted.get()) terminate()
    }

    private fun handleMessage(message: Message) {
        when (message.what) {
            EngineProtocol.HELLO -> {
                attachClient(message.replyTo ?: return)
                reply(EngineProtocol.HELLO_ACK, Bundle().apply {
                    putInt(EngineProtocol.PID, Process.myPid())
                    putInt(EngineProtocol.STATE, state)
                })
            }
            EngineProtocol.START -> start(message.data)
            EngineProtocol.STOP -> stop()
        }
    }

    private fun attachClient(replyTo: Messenger) {
        detachClient()
        val death = IBinder.DeathRecipient {
            handler.post {
                if (client?.binder == replyTo.binder) {
                    client = null
                    clientDeath = null
                    if (stopOnClientDeath || configuration == null) stop()
                }
            }
        }
        client = replyTo
        clientDeath = death
        try {
            replyTo.binder.linkToDeath(death, 0)
        } catch (_: RemoteException) {
            client = null
            clientDeath = null
            if (stopOnClientDeath || configuration == null) stop()
        }
    }

    private fun detachClient() {
        val death = clientDeath
        val endpoint = client
        client = null
        clientDeath = null
        if (death != null && endpoint != null) {
            try {
                endpoint.binder.unlinkToDeath(death, 0)
            } catch (_: NoSuchElementException) {
            }
        }
    }

    private fun start(data: Bundle) {
        if (state == EngineProtocol.STATE_STOPPING || configuration != null) return
        val args = data.getStringArray(EngineProtocol.ARGS)
        val host = data.getString(EngineProtocol.HOST)
        val port = data.getInt(EngineProtocol.PORT)
        if (args.isNullOrEmpty() || host.isNullOrBlank() || port !in 1..65535) {
            fail("Invalid engine configuration")
            return
        }
        configuration = Configuration(
            args.toList(),
            host,
            port,
            data.getString(EngineProtocol.DNS).orEmpty(),
            data.getBoolean(EngineProtocol.IPV6),
            data.getString(EngineProtocol.APP_LIST_TYPE) ?: "disable",
            data.getStringArray(EngineProtocol.APPS)?.toList().orEmpty(),
        )
        state = EngineProtocol.STATE_STARTING
        reply(EngineProtocol.CONFIGURED)
        handler.postDelayed({ if (!foregroundStarted && !stopOnClientDeath) stop() }, START_TIMEOUT)
        startNative()
    }

    private fun startNative() {
        val config = configuration ?: return
        if ((!foregroundStarted && !stopOnClientDeath) || nativeStarted || state == EngineProtocol.STATE_STOPPING) return
        if (!processStarted.compareAndSet(false, true)) {
            fail("Native session already started")
            return
        }
        nativeStarted = true
        startJob = scope.launch {
            try {
                val engine = withContext(Dispatchers.IO) { ByeDpiProxy() }
                proxy = engine
                proxyThread = runNative("ByeDPI") { engine.startProxy(config.args.toTypedArray()) }
                withTimeout(START_TIMEOUT) { awaitProxy(config.host, config.port) }
                createTunnel?.let { create ->
                    val fd = create(config)
                    tunFd = fd
                    val tunnelConfig = buildString {
                        appendLine("tunnel:")
                        appendLine("  mtu: 8500")
                        appendLine("misc:")
                        appendLine("  task-stack-size: 81920")
                        appendLine("socks5:")
                        appendLine("  address: ${config.host}")
                        appendLine("  port: ${config.port}")
                        appendLine("  udp: udp")
                    }
                    tunnelThread = runNative("HEV") { TProxyService.startTunnel(tunnelConfig, fd.fd) }
                    delay(100)
                }
                if (state != EngineProtocol.STATE_STOPPING) {
                    state = EngineProtocol.STATE_RUNNING
                    reply(EngineProtocol.STARTED)
                }
            } catch (e: CancellationException) {
                if (state != EngineProtocol.STATE_STOPPING) fail("Engine startup timed out")
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to start engine", e)
                fail(e.message ?: "Failed to start engine")
            }
        }
    }

    private fun runNative(name: String, run: () -> Int): Thread = thread(name = name) {
        val error = try {
            "$name stopped with code ${run()}"
        } catch (e: Throwable) {
            Log.e(TAG, "$name failed", e)
            "$name: ${e.message ?: "native engine failed"}"
        }
        handler.post { if (state != EngineProtocol.STATE_STOPPING) fail(error) }
    }

    private suspend fun awaitProxy(host: String, port: Int) {
        val address = when (host) {
            "0.0.0.0" -> "127.0.0.1"
            "::", "[::]" -> "::1"
            else -> host
        }
        while (true) {
            val ready = withContext(Dispatchers.IO) {
                try {
                    Socket().use { socket ->
                        socket.connect(InetSocketAddress(address, port), PROBE_TIMEOUT)
                        socket.soTimeout = PROBE_TIMEOUT
                        socket.getOutputStream().write(byteArrayOf(5, 1, 0))
                        val input = socket.getInputStream()
                        input.read() == 5 && input.read() == 0
                    }
                } catch (_: IOException) {
                    false
                }
            }
            if (ready) return
            delay(50)
        }
    }

    private fun fail(error: String) {
        if (state == EngineProtocol.STATE_STOPPING) return
        Log.e(TAG, error)
        state = EngineProtocol.STATE_STOPPING
        reply(EngineProtocol.FAILED, Bundle().apply {
            putString(EngineProtocol.ERROR, error)
        })
        terminate()
    }

    private fun reply(what: Int, data: Bundle = Bundle()) {
        try {
            client?.send(Message.obtain(null, what).apply { this.data = data })
        } catch (_: RemoteException) {
            client = null
            clientDeath = null
            if (stopOnClientDeath || configuration == null) stop()
        }
    }

    @Suppress("DEPRECATION")
    private fun terminate() {
        try {
            // DETACH, а не REMOVE: уведомление общее с TG WS и может быть ещё нужно.
            // Если TG WS тоже остановлен, ServiceManager уберёт его через RuntimeNotification.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                service.stopForeground(Service.STOP_FOREGROUND_DETACH)
            } else {
                service.stopForeground(false)
            }
            service.stopSelf()
        } finally {
            Process.killProcess(Process.myPid())
        }
    }
}
