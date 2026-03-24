package io.github.romanvht.byedpi.ewenloy.tgws

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class EwenloyTgWsProxyServer(
    private val host: String,
    private val listenPort: Int,
    private val onRouteStatus: (String) -> Unit,
    private val onStats: (String) -> Unit,
) {
    @Volatile var lastMode: String = "idle"; private set

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val wsPool = ConcurrentHashMap<Pair<Int, Boolean>, CopyOnWriteArrayList<PooledWs>>()
    private val wsBlacklist = ConcurrentHashMap.newKeySet<Pair<Int, Boolean>>()
    private val stats = Stats()

    fun isRunning(): Boolean = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(host, listenPort))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind $host:$listenPort — ${e.message}")
            running.set(false)
            return
        }

        safeExecute {
            var tick = 0
            while (running.get()) {
                try { Thread.sleep(60_000) } catch (_: InterruptedException) { break }
                if (!running.get()) break
                Log.i(TAG, "stats: ${stats.summary()}")
                tick++
                // Retry WS after redirects: permanent blacklist matched desktop but stranded many Android users on TCP-only.
                if (tick >= 30 && wsBlacklist.isNotEmpty()) {
                    tick = 0
                    wsBlacklist.clear()
                    Log.i(TAG, "WS blacklist cleared (periodic)")
                }
            }
        }

        safeExecute {
            while (running.get()) {
                val client = try {
                    serverSocket?.accept() ?: break
                } catch (e: Exception) {
                    if (running.get()) Log.e(TAG, "Accept error: ${e.message}")
                    break
                }
                safeExecute { handleClient(client) }
            }
        }

        Log.i(TAG, "TG WS Proxy started $host:$listenPort")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        wsPool.values.flatten().forEach { try { it.ws.close() } catch (_: Exception) {} }
        wsPool.clear()
        pool.shutdown()
        try {
            if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                pool.shutdownNow()
            }
        } catch (_: InterruptedException) {
            pool.shutdownNow()
        }
    }

    fun warmup() {
        if (!running.get()) return
        for (dc in listOf(2, 4, 5)) {
            if (EwenloyTelegramRanges.wsGatewayIp(dc) == null) continue
            for (isMedia in listOf(false, true)) {
                refillPoolAsync(dc to isMedia, EwenloyTelegramRanges.wsDomains(dc, isMedia))
            }
        }
    }

    private fun safeExecute(task: Runnable) {
        if (!running.get()) return
        try {
            pool.execute(task)
        } catch (_: RejectedExecutionException) {}
    }

    private fun handleClient(client: Socket) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = 10_000
            applyBuffers(client)
            doHandleClient(client)
        } catch (e: Exception) {
            if (running.get()) Log.d(TAG, "client error: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun doHandleClient(c: Socket) {
        if (!running.get()) return
        stats.total.incrementAndGet()
        val input = c.getInputStream()
        val output = c.getOutputStream()

        val greeting = ByteArray(2)
        if (!readFully(input, greeting) || greeting[0].toInt() != 5) return
        val nMethods = greeting[1].toInt() and 0xff
        if (!readFully(input, ByteArray(nMethods))) return
        output.write(byteArrayOf(0x05, 0x00)); output.flush()

        val reqHdr = ByteArray(4)
        if (!readFully(input, reqHdr)) return
        if (reqHdr[1].toInt() != 1) { sendReply(output, 0x07); return }

        val atyp = reqHdr[3].toInt() and 0xff
        val addr: String = when (atyp) {
            1 -> {
                val raw = ByteArray(4)
                if (!readFully(input, raw)) return
                raw.joinToString(".") { (it.toInt() and 0xff).toString() }
            }
            3 -> {
                val dlen = input.read()
                if (dlen <= 0) return
                val raw = ByteArray(dlen)
                if (!readFully(input, raw)) return
                raw.toString(Charsets.UTF_8)
            }
            4 -> {
                val raw = ByteArray(16)
                if (!readFully(input, raw)) return
                InetAddress.getByAddress(raw).hostAddress ?: return
            }
            else -> { sendReply(output, 0x08); return }
        }

        val portBytes = ByteArray(2)
        if (!readFully(input, portBytes)) return
        val port = ByteBuffer.wrap(portBytes).short.toInt() and 0xffff

        if (addr.contains(":")) {
            sendReply(output, 0x05); return
        }

        if (!EwenloyTelegramRanges.isTelegramIp(addr)) {
            stats.passthrough.incrementAndGet()
            directPassthrough(c, input, output, addr, port)
            return
        }

        sendReply(output, 0x00)
        c.soTimeout = 15_000

        val init = ByteArray(64)
        if (!readFully(input, init)) return
        if (isHttpTransport(init)) { stats.httpRejected.incrementAndGet(); return }

        var patched = init
        var initPatched = false
        var (dc, isMedia) = EwenloyMtProtoParser.extractDcId(init)
        var splitter: EwenloyMtProtoParser.MsgSplitter? = null

        if (dc == null) {
            EwenloyTelegramRanges.resolveByIp(addr)?.let { mapped ->
                dc = mapped.first
                isMedia = mapped.second
                patched = EwenloyMtProtoParser.patchDcId(init, if (isMedia) dc!! else -dc!!)
                initPatched = true
            }
        }

        val finalDc = dc
        if (finalDc == null) {
            stats.tcpFallback.incrementAndGet()
            lastMode = "direct"; onRouteStatus("direct")
            directTcpFallback(c, input, output, addr, port, init)
            return
        }

        val targetIp = EwenloyTelegramRanges.wsGatewayIp(finalDc)
        if (targetIp == null) {
            stats.tcpFallback.incrementAndGet()
            lastMode = "direct"; onRouteStatus("direct")
            directTcpFallback(c, input, output, addr, port, init)
            return
        }

        val dcKey = finalDc to isMedia

        if (wsBlacklist.contains(dcKey)) {
            stats.tcpFallback.incrementAndGet()
            lastMode = "direct"; onRouteStatus("direct")
            directTcpFallback(c, input, output, addr, port, init)
            return
        }

        // Always use full handshake timeout (10s). Short 2s after failure caused
        // almost-only TCP fallback on mobile; Flowseal desktop tolerates 2s — Android does not.
        val wsTimeout = WS_CONNECT_TIMEOUT_MS
        val domains = EwenloyTelegramRanges.wsDomains(finalDc, isMedia)

        val pooled = wsPool[dcKey]?.removeFirstOrNull()?.let {
            if (System.currentTimeMillis() - it.createdAtMs <= WS_POOL_MAX_AGE) it.ws
            else { try { it.ws.close() } catch (_: Exception) {}; null }
        }

        var redirectCount = 0
        var allRedirects = true
        val ws = pooled ?: run {
            var result: EwenloyRawWebSocket? = null
            for (domain in domains) {
                if (!running.get()) break
                try {
                    result = EwenloyRawWebSocket.connect(targetIp, domain, wsTimeout)
                    allRedirects = false
                    break
                } catch (ex: EwenloyWsHandshakeException) {
                    stats.wsErrors.incrementAndGet()
                    if (ex.isRedirect) redirectCount++ else allRedirects = false
                } catch (_: Exception) {
                    stats.wsErrors.incrementAndGet()
                    allRedirects = false
                }
            }
            result
        }

        if (ws == null) {
            if (redirectCount > 0 && allRedirects) {
                wsBlacklist.add(dcKey)
            }
            stats.tcpFallback.incrementAndGet()
            lastMode = "direct"; onRouteStatus("direct")
            directTcpFallback(c, input, output, addr, port, init)
            return
        }

        stats.wsConnections.incrementAndGet()
        lastMode = "ws"; onRouteStatus("ws")

        if (initPatched) {
            splitter = try { EwenloyMtProtoParser.MsgSplitter(init) } catch (_: Exception) { null }
        }

        ws.sendBinary(patched)
        c.soTimeout = 0
        bridgeWs(input, output, ws, splitter)
        refillPoolAsync(dcKey, domains)
    }

    private fun directPassthrough(
        clientSock: Socket, clientIn: InputStream, clientOut: OutputStream,
        addr: String, port: Int,
    ) {
        try {
            val remote = Socket()
            remote.tcpNoDelay = true
            remote.connect(InetSocketAddress(addr, port), 10_000)
            remote.soTimeout = 0
            applyBuffers(remote)
            sendReply(clientOut, 0x00)
            clientSock.soTimeout = 0
            bridgeTcp(clientIn, clientOut, remote.getInputStream(), remote.getOutputStream())
            try { remote.close() } catch (_: Exception) {}
        } catch (e: Exception) {
            sendReply(clientOut, 0x05)
        }
    }

    private fun directTcpFallback(
        clientSock: Socket, clientIn: InputStream, clientOut: OutputStream,
        destIp: String, destPort: Int, initData: ByteArray,
    ) {
        try {
            val remote = Socket()
            remote.tcpNoDelay = true
            remote.connect(InetSocketAddress(destIp, destPort), 10_000)
            remote.soTimeout = 0
            applyBuffers(remote)
            val rout = remote.getOutputStream()
            rout.write(initData); rout.flush()
            clientSock.soTimeout = 0
            bridgeTcp(clientIn, clientOut, remote.getInputStream(), rout)
            try { remote.close() } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.d(TAG, "[$destIp:$destPort] TCP fallback err: ${e.message}")
        }
    }

    private fun bridgeWs(
        clientIn: InputStream, clientOut: OutputStream,
        ws: EwenloyRawWebSocket, splitter: EwenloyMtProtoParser.MsgSplitter?,
    ) {
        val closed = AtomicBoolean(false)
        fun teardown() {
            if (!closed.compareAndSet(false, true)) return
            try { ws.close() } catch (_: Exception) {}
            try { clientOut.close() } catch (_: Exception) {}
            try { clientIn.close() } catch (_: Exception) {}
        }
        val up = pool.submit {
            try {
                val buf = ByteArray(65_536)
                while (!closed.get()) {
                    val n = clientIn.read(buf); if (n <= 0) break
                    if (splitter != null) {
                        val parts = splitter.split(buf.copyOf(n))
                        if (parts.size > 1) ws.sendBatch(parts) else ws.sendBinary(parts[0])
                    } else {
                        ws.sendBinary(buf, 0, n)
                    }
                    stats.bytesUp.addAndGet(n.toLong())
                }
            } catch (_: Exception) {}
            finally { teardown() }
        }
        val down = pool.submit {
            try {
                while (!closed.get()) {
                    val frame = ws.receive() ?: break
                    clientOut.write(frame); clientOut.flush()
                    stats.bytesDown.addAndGet(frame.size.toLong())
                }
            } catch (_: Exception) {}
            finally { teardown() }
        }
        try { up.get() } catch (_: Exception) {}
        try { down.get() } catch (_: Exception) {}
        teardown()
    }

    private fun bridgeTcp(
        clientIn: InputStream, clientOut: OutputStream,
        remoteIn: InputStream, remoteOut: OutputStream,
    ) {
        val closed = AtomicBoolean(false)
        fun teardown() {
            if (!closed.compareAndSet(false, true)) return
            try { clientIn.close() } catch (_: Exception) {}
            try { clientOut.close() } catch (_: Exception) {}
            try { remoteIn.close() } catch (_: Exception) {}
            try { remoteOut.close() } catch (_: Exception) {}
        }
        val up = pool.submit {
            try { pipe(clientIn, remoteOut, true) } catch (_: Exception) {}
            finally { teardown() }
        }
        val down = pool.submit {
            try { pipe(remoteIn, clientOut, false) } catch (_: Exception) {}
            finally { teardown() }
        }
        try { up.get() } catch (_: Exception) {}
        try { down.get() } catch (_: Exception) {}
        teardown()
    }

    private fun pipe(input: InputStream, output: OutputStream, isUp: Boolean) {
        val buf = ByteArray(65_536)
        while (true) {
            val n = input.read(buf); if (n <= 0) break
            output.write(buf, 0, n); output.flush()
            if (isUp) stats.bytesUp.addAndGet(n.toLong()) else stats.bytesDown.addAndGet(n.toLong())
        }
    }

    private fun applyBuffers(s: Socket) {
        try { s.receiveBufferSize = BUF_SIZE; s.sendBufferSize = BUF_SIZE } catch (_: Exception) {}
    }

    private fun refillPoolAsync(key: Pair<Int, Boolean>, domains: List<String>) {
        safeExecute {
            try {
                val (dc, _) = key
                val ip = EwenloyTelegramRanges.wsGatewayIp(dc) ?: return@safeExecute
                val bucket = wsPool.getOrPut(key) { CopyOnWriteArrayList() }
                while (bucket.size < 4 && running.get()) {
                    val ws = domains.firstNotNullOfOrNull { domain ->
                        try { EwenloyRawWebSocket.connect(ip, domain, WS_CONNECT_TIMEOUT_MS) } catch (_: Exception) { null }
                    } ?: break
                    bucket.add(PooledWs(ws, System.currentTimeMillis()))
                }
            } catch (_: Exception) {}
        }
    }

    private fun sendReply(out: OutputStream, code: Int) {
        try { out.write(byteArrayOf(0x05, code.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0)); out.flush() }
        catch (_: Exception) {}
    }

    private fun readFully(input: InputStream, dst: ByteArray): Boolean {
        var off = 0
        while (off < dst.size) { val n = input.read(dst, off, dst.size - off); if (n <= 0) return false; off += n }
        return true
    }

    private fun isHttpTransport(data: ByteArray): Boolean {
        val t = data.copyOfRange(0, minOf(8, data.size)).toString(Charsets.US_ASCII)
        return t.startsWith("GET ") || t.startsWith("POST ") || t.startsWith("HEAD ") || t.startsWith("OPTIONS ")
    }

    private data class PooledWs(val ws: EwenloyRawWebSocket, val createdAtMs: Long)

    private class Stats {
        val total = AtomicLong(0); val wsConnections = AtomicLong(0)
        val tcpFallback = AtomicLong(0); val passthrough = AtomicLong(0)
        val httpRejected = AtomicLong(0); val wsErrors = AtomicLong(0)
        val bytesUp = AtomicLong(0); val bytesDown = AtomicLong(0)
        fun summary() = "total=${total.get()} ws=${wsConnections.get()} tcp=${tcpFallback.get()} " +
            "pass=${passthrough.get()} up=${bytesUp.get()} down=${bytesDown.get()}"
    }

    companion object {
        private const val TAG = "EwenloyTgWsProxy"
        private const val BUF_SIZE = 256 * 1024
        private const val WS_POOL_MAX_AGE = 120_000L
        private const val WS_CONNECT_TIMEOUT_MS = 10_000
    }
}
