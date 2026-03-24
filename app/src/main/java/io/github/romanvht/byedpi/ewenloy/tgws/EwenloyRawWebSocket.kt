package io.github.romanvht.byedpi.ewenloy.tgws

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

class EwenloyWsHandshakeException(
    val statusCode: Int,
    val statusLine: String,
    val location: String? = null,
) : IllegalStateException(statusLine) {
    val isRedirect: Boolean
        get() = statusCode in setOf(301, 302, 303, 307, 308)
}

class EwenloyRawWebSocket private constructor(
    private val socket: SSLSocket,
    private val input: BufferedInputStream,
    private val output: BufferedOutputStream,
) {
    fun sendBinary(payload: ByteArray) {
        val frame = buildFrame(0x2, payload, true)
        output.write(frame)
        output.flush()
    }

    fun receive(): ByteArray? {
        while (true) {
            val hdr1 = input.read()
            if (hdr1 < 0) return null
            val hdr2 = input.read()
            if (hdr2 < 0) return null
            val opcode = hdr1 and 0x0f
            val masked = (hdr2 and 0x80) != 0
            var len = (hdr2 and 0x7f).toLong()
            if (len == 126L) len = readExact(2).let { ((it[0].toInt() and 0xff) shl 8 or (it[1].toInt() and 0xff)).toLong() }
            else if (len == 127L) len = readExact(8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
            val mask = if (masked) readExact(4) else null
            val payload = readExact(len.toInt())
            if (masked && mask != null) {
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }
            when (opcode) {
                0x2, 0x1 -> return payload
                0x8 -> return null
                0x9 -> {
                    val pong = buildFrame(0xA, payload, true)
                    output.write(pong)
                    output.flush()
                }
            }
        }
    }

    fun sendBatch(parts: List<ByteArray>) {
        for (part in parts) {
            val frame = buildFrame(0x2, part, true)
            output.write(frame)
        }
        output.flush()
    }

    fun close() = runCatching { socket.close() }

    private fun readExact(count: Int): ByteArray {
        val buf = ByteArray(count)
        var off = 0
        while (off < count) {
            val n = input.read(buf, off, count - off)
            if (n <= 0) throw IllegalStateException("Unexpected EOF")
            off += n
        }
        return buf
    }

    companion object {
        private val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        fun connect(ip: String, domain: String, timeoutMs: Int = 10000): EwenloyRawWebSocket {
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf(trustAll), SecureRandom())
            val plain = Socket()
            plain.connect(InetSocketAddress(ip, 443), timeoutMs)
            val ssl = sslContext.socketFactory.createSocket(plain, domain, 443, true) as SSLSocket
            ssl.soTimeout = timeoutMs
            ssl.startHandshake()

            val input = BufferedInputStream(ssl.getInputStream())
            val output = BufferedOutputStream(ssl.getOutputStream())
            val wsKey = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
            val req = buildString {
                append("GET /apiws HTTP/1.1\r\n")
                append("Host: $domain\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Key: $wsKey\r\n")
                append("Sec-WebSocket-Version: 13\r\n")
                append("Sec-WebSocket-Protocol: binary\r\n")
                append("Origin: https://web.telegram.org\r\n")
                append("User-Agent: Mozilla/5.0\r\n\r\n")
            }
            output.write(req.toByteArray())
            output.flush()

            val firstLine = readHeaderLine(input)
            if (!firstLine.contains(" 101 ")) {
                val code = firstLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
                var location: String? = null
                while (true) {
                    val line = readHeaderLine(input)
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        val key = line.substring(0, idx).trim().lowercase()
                        val value = line.substring(idx + 1).trim()
                        if (key == "location") location = value
                    }
                }
                throw EwenloyWsHandshakeException(code, firstLine, location)
            }
            while (true) {
                val line = readHeaderLine(input)
                if (line.isEmpty()) break
            }
            ssl.soTimeout = 0
            return EwenloyRawWebSocket(ssl, input, output)
        }

        private fun readHeaderLine(input: BufferedInputStream): String {
            val bytes = ArrayList<Byte>()
            while (true) {
                val b = input.read()
                if (b < 0) break
                bytes.add(b.toByte())
                if (bytes.size >= 2 && bytes[bytes.lastIndex - 1] == '\r'.code.toByte() && bytes.last() == '\n'.code.toByte()) {
                    break
                }
            }
            return bytes.toByteArray().toString(Charsets.UTF_8).trim()
        }

        private fun buildFrame(opcode: Int, payload: ByteArray, mask: Boolean): ByteArray {
            val out = ArrayList<Byte>(payload.size + 16)
            out.add((0x80 or opcode).toByte())
            val maskBit = if (mask) 0x80 else 0
            when {
                payload.size < 126 -> out.add((maskBit or payload.size).toByte())
                payload.size <= 0xffff -> {
                    out.add((maskBit or 126).toByte())
                    out.add(((payload.size shr 8) and 0xff).toByte())
                    out.add((payload.size and 0xff).toByte())
                }
                else -> {
                    out.add((maskBit or 127).toByte())
                    for (i in 7 downTo 0) out.add(((payload.size.toLong() shr (i * 8)) and 0xff).toByte())
                }
            }
            if (!mask) {
                payload.forEach { out.add(it) }
                return out.toByteArray()
            }
            val maskKey = ByteArray(4).also { SecureRandom().nextBytes(it) }
            maskKey.forEach { out.add(it) }
            payload.forEachIndexed { i, b -> out.add((b.toInt() xor maskKey[i % 4].toInt()).toByte()) }
            return out.toByteArray()
        }
    }
}
