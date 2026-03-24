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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class EwenloyTgWsProxyServer(
    private val host: String,
    private val listenPort: Int,
    private val byeDpiHost: String,
    private val byeDpiPort: Int,
    private val onRouteStatus: (String) -> Unit,
    private val onStats: (String) -> Unit,
) {
    @Volatile var lastMode: String = "idle"; private set

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    private val wsPool = ConcurrentHashMap<Pair<Int, Boolean>, CopyOnWriteArrayList<PooledWs>>()
    private val wsBlacklist = ConcurrentHashMap.newKeySet<Pair<Int, Boolean>>()
    private val dcFailUntilMs = ConcurrentHashMap<Pair<Int, Boolean>, Long>()
    private val wsPoolMaxAgeMs = 120_000L
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
        pool.execute {
            while (running.get()) {
                try { Thread.sleep(60_000) } catch (_: InterruptedException) { break }
                if (!running.get()) break
                val msg = "stats: ${stats.summary()} | ws_bl=${wsBlacklist.joinToString { "DC${it.first}${if (it.second) "m" else ""}" }.ifBlank { "none" }}"
                Log.i(TAG, msg)
                onStats(msg)
            }
        }
        pool.execute {
            while (running.get()) {
                val client = try {
                    serverSocket?.accept() ?: break
                } catch (e: Exception) {
                    if (running.get()) Log.e(TAG, "Accept error: ${e.message}")
                    break
                }
                pool.execute { handleClient(client) }
            }
        }
        Log.i(TAG, "=== Proxy started $host:$listenPort → byedpi=$byeDpiHost:$byeDpiPort ===")
        onStats("proxy_started port=$listenPort backend=$byeDpiPort")
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        wsPool.values.flatten().forEach { runCatching { it.ws.close() } }
        wsPool.clear()
        pool.shutdownNow()
    }

    /** Pre-open WS connections like tg-ws-proxy.py warmup (reduces first-connect latency). */
    fun warmup() {
        if (!running.get()) return
        val dcs = listOf(2, 4, 5)
        for (dc in dcs) {
            if (EwenloyTelegramRanges.wsGatewayIp(dc) == null) continue
            for (isMedia in listOf(false, true)) {
                val domains = EwenloyTelegramRanges.wsDomains(dc, isMedia)
                refillPoolAsync(dc to isMedia, domains)
            }
        }
        Log.i(TAG, "WS pool warmup scheduled for DCs $dcs")
    }

    private fun handleClient(client: Socket) {
        client.tcpNoDelay = true
        client.soTimeout = 0
        applyBuffers(client)
        client.use { c ->
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
            val addr: String
            val rawAddr: ByteArray
            when (atyp) {
                1 -> {
                    rawAddr = ByteArray(4)
                    if (!readFully(input, rawAddr)) return
                    addr = rawAddr.joinToString(".") { (it.toInt() and 0xff).toString() }
                }
                3 -> {
                    val dlen = input.read()
                    if (dlen <= 0) return
                    rawAddr = ByteArray(dlen)
                    if (!readFully(input, rawAddr)) return
                    addr = rawAddr.toString(Charsets.UTF_8)
                }
                4 -> {
                    rawAddr = ByteArray(16)
                    if (!readFully(input, rawAddr)) return
                    addr = InetAddress.getByAddress(rawAddr).hostAddress ?: return
                }
                else -> { sendReply(output, 0x08); return }
            }

            val portBytes = ByteArray(2)
            if (!readFully(input, portBytes)) return
            val port = ByteBuffer.wrap(portBytes).short.toInt() and 0xffff

            if (!EwenloyTelegramRanges.isTelegramIp(addr)) {
                stats.passthrough.incrementAndGet()
                routeViaByeDpi(c, input, output, addr, atyp, rawAddr, port)
                return
            }

            sendReply(output, 0x00)

            val init = ByteArray(64)
            if (!readFully(input, init)) return
            if (isHttpTransport(init)) return

            var patched = init
            var (dc, isMedia) = EwenloyMtProtoParser.extractDcId(init)
            var splitter: EwenloyMtProtoParser.MsgSplitter? = null
            if (dc == null) {
                EwenloyTelegramRanges.resolveByIp(addr)?.let { mapped ->
                    dc = mapped.first
                    isMedia = mapped.second
                    patched = EwenloyMtProtoParser.patchDcId(init, if (isMedia) dc!! else -dc!!)
                    splitter = runCatching { EwenloyMtProtoParser.MsgSplitter(init) }.getOrNull()
                }
            }

            val finalDc = dc
            if (finalDc == null) {
                Log.w(TAG, "[$addr] unknown DC → ByeDPI")
                stats.byeDpiConnections.incrementAndGet()
                lastMode = "byedpi"; onRouteStatus("byedpi")
                fallbackViaByeDpi(c, input, output, addr, port, init)
                return
            }

            val targetIp = EwenloyTelegramRanges.wsGatewayIp(finalDc)
            if (targetIp == null) {
                Log.w(TAG, "[$addr] DC$finalDc no WS gateway → ByeDPI")
                stats.byeDpiConnections.incrementAndGet()
                lastMode = "byedpi"; onRouteStatus("byedpi")
                fallbackViaByeDpi(c, input, output, addr, port, init)
                return
            }

            val dcKey = finalDc to isMedia
            val now = System.currentTimeMillis()
            val mediaTag = if (isMedia) " media" else ""

            if (wsBlacklist.contains(dcKey)) {
                Log.d(TAG, "[$addr] DC$finalDc$mediaTag blacklisted → ByeDPI")
                stats.byeDpiConnections.incrementAndGet()
                lastMode = "byedpi"; onRouteStatus("byedpi")
                fallbackViaByeDpi(c, input, output, addr, port, init)
                return
            }

            val wsTimeout = if ((dcFailUntilMs[dcKey] ?: 0L) > now) 2_000 else 10_000
            val domains = EwenloyTelegramRanges.wsDomains(finalDc, isMedia)

            val pooled = wsPool[dcKey]?.removeFirstOrNull()?.let {
                if (System.currentTimeMillis() - it.createdAtMs <= wsPoolMaxAgeMs) it.ws
                else { runCatching { it.ws.close() }; null }
            }.also { if (it != null) stats.poolHits.incrementAndGet() else stats.poolMisses.incrementAndGet() }

            var redirectCount = 0
            val ws = pooled ?: domains.firstNotNullOfOrNull { domain ->
                runCatching {
                    Log.i(TAG, "[$addr] DC$finalDc$mediaTag → wss://$domain/apiws via $targetIp (${wsTimeout}ms)")
                    EwenloyRawWebSocket.connect(targetIp, domain, wsTimeout)
                }.getOrElse { ex ->
                    when (ex) {
                        is EwenloyWsHandshakeException -> {
                            stats.wsErrors.incrementAndGet()
                            if (ex.isRedirect) { redirectCount++; Log.w(TAG, "[$addr] DC$finalDc$mediaTag redirect ${ex.statusCode}") }
                            else Log.w(TAG, "[$addr] DC$finalDc$mediaTag WS fail: ${ex.statusLine}")
                        }
                        else -> { stats.wsErrors.incrementAndGet(); Log.w(TAG, "[$addr] DC$finalDc$mediaTag WS err: ${ex.message}") }
                    }
                    null
                }
            }

            if (ws == null) {
                if (redirectCount > 0 && redirectCount == domains.size) {
                    wsBlacklist.add(dcKey)
                    Log.w(TAG, "[$addr] DC$finalDc$mediaTag blacklisted (all redirects)")
                }
                dcFailUntilMs[dcKey] = System.currentTimeMillis() + 30_000
                Log.i(TAG, "[$addr] DC$finalDc$mediaTag WS failed → ByeDPI fallback")
                stats.byeDpiConnections.incrementAndGet()
                lastMode = "byedpi"; onRouteStatus("byedpi")
                fallbackViaByeDpi(c, input, output, addr, port, init)
                return
            }

            dcFailUntilMs.remove(dcKey)
            stats.wsConnections.incrementAndGet()
            lastMode = "ws"; onRouteStatus("ws")
            Log.i(TAG, "[$addr] DC$finalDc$mediaTag → WS OK")
            ws.sendBinary(patched)
            c.soTimeout = 0
            bridgeWs(input, output, ws, splitter)
            refillPoolAsync(dcKey, domains)
        }
    }

    private fun routeViaByeDpi(
        clientSock: Socket,
        clientIn: InputStream, clientOut: OutputStream,
        addr: String, atyp: Int, rawAddr: ByteArray, port: Int,
    ) {
        try {
            Socket().use { backend ->
                backend.reuseAddress = true; backend.tcpNoDelay = true
                backend.connect(InetSocketAddress(byeDpiHost, byeDpiPort), 8_000)
                backend.soTimeout = 0
                applyBuffers(backend)
                val bin = backend.getInputStream()
                val bout = backend.getOutputStream()

                bout.write(byteArrayOf(0x05, 0x01, 0x00)); bout.flush()
                val auth = ByteArray(2)
                if (!readFully(bin, auth)) { sendReply(clientOut, 0x05); return }

                bout.write(buildSocks5Connect(atyp, rawAddr, port)); bout.flush()

                val resp = ByteArray(4)
                if (!readFully(bin, resp)) { sendReply(clientOut, 0x05); return }
                val repCode = resp[1].toInt() and 0xff
                val bindAtyp = resp[3].toInt() and 0xff
                val bindAddrLen = when (bindAtyp) {
                    1 -> 4; 4 -> 16
                    3 -> bin.read().also { if (it < 0) { sendReply(clientOut, 0x05); return } }
                    else -> { sendReply(clientOut, 0x05); return }
                }
                if (!readFully(bin, ByteArray(bindAddrLen + 2))) { sendReply(clientOut, 0x05); return }
                if (repCode != 0) { sendReply(clientOut, repCode); return }

                sendReply(clientOut, 0x00)
                stats.byeDpiConnections.incrementAndGet()
                clientSock.soTimeout = 0
                bridgeTcp(clientIn, clientOut, bin, bout)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$addr:$port] ByeDPI route failed: ${e.message}")
            sendReply(clientOut, 0x05)
        }
    }

    private fun fallbackViaByeDpi(
        clientSock: Socket,
        clientIn: InputStream, clientOut: OutputStream,
        destIp: String, destPort: Int, initData: ByteArray,
    ) {
        try {
            Socket().use { backend ->
                backend.reuseAddress = true; backend.tcpNoDelay = true
                backend.connect(InetSocketAddress(byeDpiHost, byeDpiPort), 8_000)
                backend.soTimeout = 0
                applyBuffers(backend)
                val bin = backend.getInputStream()
                val bout = backend.getOutputStream()

                bout.write(byteArrayOf(0x05, 0x01, 0x00)); bout.flush()
                val authResp = ByteArray(2)
                if (!readFully(bin, authResp) || authResp[0].toInt() != 5) return

                val ipBytes = InetAddress.getByName(destIp).address
                bout.write(buildSocks5Connect(if (ipBytes.size == 4) 1 else 4, ipBytes, destPort)); bout.flush()

                val resp = ByteArray(4)
                if (!readFully(bin, resp)) return
                val repCode = resp[1].toInt() and 0xff
                val bindAtyp = resp[3].toInt() and 0xff
                val bindAddrLen = when (bindAtyp) {
                    1 -> 4; 4 -> 16; 3 -> bin.read().also { if (it < 0) return }
                    else -> return
                }
                if (!readFully(bin, ByteArray(bindAddrLen + 2))) return
                if (repCode != 0) { Log.e(TAG, "[$destIp] ByeDPI CONNECT rejected ($repCode)"); return }

                Log.i(TAG, "[$destIp:$destPort] → ByeDPI fallback OK")
                bout.write(initData); bout.flush()
                clientSock.soTimeout = 0
                bridgeTcp(clientIn, clientOut, bin, bout)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$destIp] ByeDPI fallback: ${e.message}")
        }
    }

    private fun bridgeWs(
        clientIn: InputStream, clientOut: OutputStream,
        ws: EwenloyRawWebSocket, splitter: EwenloyMtProtoParser.MsgSplitter? = null,
    ) {
        val closed = AtomicBoolean(false)
        fun teardown() {
            if (closed.compareAndSet(false, true)) {
                runCatching { ws.close() }
                runCatching { clientOut.close() }
                runCatching { clientIn.close() }
            }
        }

        val up = pool.submit {
            try {
                val buf = ByteArray(65_536)
                while (true) {
                    val n = clientIn.read(buf); if (n <= 0) break
                    if (splitter != null) {
                        val parts = splitter.split(buf.copyOf(n))
                        if (parts.size > 1) ws.sendBatch(parts) else ws.sendBinary(parts[0])
                    } else {
                        ws.sendBinary(buf, 0, n)
                    }
                    stats.bytesUp.addAndGet(n.toLong())
                }
            } catch (_: Exception) {
            } finally { teardown() }
        }
        val down = pool.submit {
            try {
                while (true) {
                    val frame = ws.receive() ?: break
                    clientOut.write(frame); clientOut.flush()
                    stats.bytesDown.addAndGet(frame.size.toLong())
                }
            } catch (_: Exception) {
            } finally { teardown() }
        }
        runCatching { up.get() }; runCatching { down.get() }
        teardown()
    }

    private fun bridgeTcp(
        clientIn: InputStream, clientOut: OutputStream,
        remoteIn: InputStream, remoteOut: OutputStream,
    ) {
        val closed = AtomicBoolean(false)
        fun teardown() {
            if (closed.compareAndSet(false, true)) {
                runCatching { clientIn.close() }
                runCatching { clientOut.close() }
                runCatching { remoteIn.close() }
                runCatching { remoteOut.close() }
            }
        }

        val up = pool.submit {
            try { pipe(clientIn, remoteOut) }
            catch (_: Exception) {}
            finally { teardown() }
        }
        val down = pool.submit {
            try { pipe(remoteIn, clientOut) }
            catch (_: Exception) {}
            finally { teardown() }
        }
        runCatching { up.get() }; runCatching { down.get() }
        teardown()
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        val buf = ByteArray(65_536)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            output.write(buf, 0, n)
            output.flush()
        }
    }

    private fun applyBuffers(s: Socket) {
        runCatching {
            s.receiveBufferSize = BUF_SIZE
            s.sendBufferSize = BUF_SIZE
        }
    }

    private fun refillPoolAsync(key: Pair<Int, Boolean>, domains: List<String>) {
        pool.submit {
            runCatching {
                val (dc, _) = key
                val ip = EwenloyTelegramRanges.wsGatewayIp(dc) ?: return@submit
                val bucket = wsPool.getOrPut(key) { CopyOnWriteArrayList() }
                while (bucket.size < 4 && running.get()) {
                    val ws = domains.firstNotNullOfOrNull { domain ->
                        runCatching { EwenloyRawWebSocket.connect(ip, domain, 8_000) }.getOrNull()
                    } ?: break
                    bucket.add(PooledWs(ws, System.currentTimeMillis()))
                }
            }
        }
    }

    private fun sendReply(out: OutputStream, code: Int) {
        runCatching { out.write(byteArrayOf(0x05, code.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0)); out.flush() }
    }

    private fun buildSocks5Connect(atyp: Int, rawAddr: ByteArray, port: Int): ByteArray {
        val p = byteArrayOf(((port shr 8) and 0xff).toByte(), (port and 0xff).toByte())
        return when (atyp) {
            1 -> byteArrayOf(0x05, 0x01, 0x00, 0x01) + rawAddr + p
            3 -> byteArrayOf(0x05, 0x01, 0x00, 0x03, rawAddr.size.toByte()) + rawAddr + p
            4 -> byteArrayOf(0x05, 0x01, 0x00, 0x04) + rawAddr + p
            else -> byteArrayOf(0x05, 0x01, 0x00, 0x01) + rawAddr + p
        }
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
        val byeDpiConnections = AtomicLong(0); val passthrough = AtomicLong(0)
        val wsErrors = AtomicLong(0); val poolHits = AtomicLong(0)
        val poolMisses = AtomicLong(0); val bytesUp = AtomicLong(0); val bytesDown = AtomicLong(0)
        fun summary() = "total=${total.get()} ws=${wsConnections.get()} byedpi=${byeDpiConnections.get()} " +
            "pass=${passthrough.get()} err=${wsErrors.get()} pool=${poolHits.get()}/${poolHits.get() + poolMisses.get()} " +
            "up=${bytesUp.get()} down=${bytesDown.get()}"
    }

    companion object {
        private const val TAG = "EwenloyTgWsProxy"
        private const val BUF_SIZE = 256 * 1024
    }
}
