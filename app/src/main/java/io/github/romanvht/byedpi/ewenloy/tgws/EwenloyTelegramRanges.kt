package io.github.romanvht.byedpi.ewenloy.tgws

import java.net.InetAddress
import kotlin.math.abs

object EwenloyTelegramRanges {
    private val ranges = listOf(
        "185.76.151.0" to "185.76.151.255",
        "149.154.160.0" to "149.154.175.255",
        "91.105.192.0" to "91.105.193.255",
        "91.108.0.0" to "91.108.255.255",
    ).map { (a, b) -> ipv4ToInt(a) to ipv4ToInt(b) }

    // ip -> (dc, isMedia)
    private val ipToDc = mapOf(
        "149.154.175.50" to (1 to false), "149.154.175.51" to (1 to false),
        "149.154.175.53" to (1 to false), "149.154.175.54" to (1 to false),
        "149.154.175.52" to (1 to true),
        "149.154.167.41" to (2 to false), "149.154.167.50" to (2 to false),
        "149.154.167.51" to (2 to false), "149.154.167.220" to (2 to false),
        "95.161.76.100" to (2 to false),
        "149.154.167.151" to (2 to true), "149.154.167.222" to (2 to true),
        "149.154.167.223" to (2 to true), "149.154.162.123" to (2 to true),
        "149.154.175.100" to (3 to false), "149.154.175.101" to (3 to false),
        "149.154.175.102" to (3 to true),
        "149.154.167.91" to (4 to false), "149.154.167.92" to (4 to false),
        "149.154.164.250" to (4 to true), "149.154.166.120" to (4 to true),
        "149.154.166.121" to (4 to true), "149.154.167.118" to (4 to true),
        "149.154.165.111" to (4 to true),
        "91.108.56.100" to (5 to false), "91.108.56.101" to (5 to false),
        "91.108.56.116" to (5 to false), "91.108.56.126" to (5 to false),
        "149.154.171.5" to (5 to false),
        "91.108.56.102" to (5 to true), "91.108.56.128" to (5 to true),
        "91.108.56.151" to (5 to true),
        "91.105.192.100" to (203 to false),
    )

    private val dcOverrides = mapOf(203 to 2)

    // Hardcoded WS gateway IPs — matches flowseal tg-ws-proxy.py defaults.
    // TCP connects to this IP; TLS SNI uses kws*.web.telegram.org for routing.
    private val dcOpt: Map<Int, String> = mapOf(
        1 to "149.154.167.220",
        2 to "149.154.167.220",
        3 to "149.154.167.220",
        4 to "149.154.167.220",
        5 to "149.154.167.220",
    )

    fun wsGatewayIp(dc: Int): String? {
        val mappedDc = dcOverrides[dc] ?: dc
        return dcOpt[mappedDc]
    }

    fun isTelegramIp(ip: String): Boolean {
        val value = ipv4ToIntOrNull(ip) ?: return false
        return ranges.any { value in it.first..it.second }
    }

    fun resolveByIp(ip: String): Pair<Int, Boolean>? = ipToDc[ip]

    fun wsDomains(dc: Int, isMedia: Boolean): List<String> {
        val mappedDc = dcOverrides[dc] ?: dc
        return if (isMedia) {
            listOf("kws$mappedDc-1.web.telegram.org", "kws$mappedDc.web.telegram.org")
        } else {
            listOf("kws$mappedDc.web.telegram.org", "kws$mappedDc-1.web.telegram.org")
        }
    }

    private fun ipv4ToInt(ip: String): Int {
        val bytes = InetAddress.getByName(ip).address
        return ((bytes[0].toInt() and 0xff) shl 24) or
            ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or
            (bytes[3].toInt() and 0xff)
    }

    private fun ipv4ToIntOrNull(ip: String): Int? = runCatching { ipv4ToInt(ip) }.getOrNull()
}
