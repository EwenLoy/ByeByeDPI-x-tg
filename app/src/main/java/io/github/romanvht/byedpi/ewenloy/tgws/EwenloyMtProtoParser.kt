package io.github.romanvht.byedpi.ewenloy.tgws

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

object EwenloyMtProtoParser {
    private val validProtos = setOf(0xEFEFEFEF.toInt(), 0xEEEEEEEE.toInt(), 0xDDDDDDDD.toInt())

    fun extractDcId(init: ByteArray): Pair<Int?, Boolean> {
        return runCatching {
            if (init.size < 64) return null to false
            val key = init.copyOfRange(8, 40)
            val iv = init.copyOfRange(40, 56)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val ks = cipher.update(ByteArray(64))
            val plainTail = ByteArray(8)
            for (i in 0 until 8) plainTail[i] = (init[56 + i].toInt() xor ks[56 + i].toInt()).toByte()
            val bb = ByteBuffer.wrap(plainTail).order(ByteOrder.LITTLE_ENDIAN)
            val proto = bb.int
            val dcRaw = bb.short.toInt()
            val dc = abs(dcRaw)
            if (proto in validProtos && (dc in 1..5 || dc == 203)) dc to (dcRaw < 0) else null to false
        }.getOrElse { null to false }
    }

    fun patchDcId(init: ByteArray, dcRaw: Int): ByteArray {
        if (init.size < 64) return init
        return runCatching {
            val key = init.copyOfRange(8, 40)
            val iv = init.copyOfRange(40, 56)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val ks = cipher.update(ByteArray(64))
            val dcLe = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(dcRaw.toShort()).array()
            val patched = init.copyOf()
            patched[60] = (ks[60].toInt() xor dcLe[0].toInt()).toByte()
            patched[61] = (ks[61].toInt() xor dcLe[1].toInt()).toByte()
            patched
        }.getOrDefault(init)
    }

    class MsgSplitter(init: ByteArray) {
        private val cipher: Cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            val key = init.copyOfRange(8, 40)
            val iv = init.copyOfRange(40, 56)
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            update(ByteArray(64))
        }

        fun split(chunk: ByteArray): List<ByteArray> {
            val plain = cipher.update(chunk)
            var pos = 0
            val boundaries = ArrayList<Int>()
            while (pos < plain.size) {
                val first = plain[pos].toInt() and 0xff
                val msgLen: Int
                if (first == 0x7f) {
                    if (pos + 4 > plain.size) break
                    val packed = ByteBuffer.wrap(byteArrayOf(plain[pos + 1], plain[pos + 2], plain[pos + 3], 0)).order(ByteOrder.LITTLE_ENDIAN).int
                    msgLen = packed * 4
                    pos += 4
                } else {
                    msgLen = first * 4
                    pos += 1
                }
                if (msgLen <= 0 || pos + msgLen > plain.size) break
                pos += msgLen
                boundaries.add(pos)
            }
            if (boundaries.size <= 1) return listOf(chunk)
            val out = ArrayList<ByteArray>(boundaries.size + 1)
            var prev = 0
            for (b in boundaries) {
                out.add(chunk.copyOfRange(prev, b))
                prev = b
            }
            if (prev < chunk.size) out.add(chunk.copyOfRange(prev, chunk.size))
            return out
        }
    }
}
