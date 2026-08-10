package com.example.util

import android.net.Uri
import android.util.Base64
import com.example.model.ProtocolType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

object PingUtil {

    data class ServerTarget(val host: String, val port: Int)

    fun extractServerTarget(rawConfig: String, protocol: ProtocolType): ServerTarget? {
        val trimmed = rawConfig.trim()
        if (trimmed.isBlank()) return null

        return try {
            when {
                trimmed.startsWith("vmess://", ignoreCase = true) -> parseVmess(trimmed)
                trimmed.startsWith("vless://", ignoreCase = true) -> parseUriBased(trimmed)
                trimmed.startsWith("trojan://", ignoreCase = true) -> parseUriBased(trimmed)
                trimmed.startsWith("tuic://", ignoreCase = true) -> parseUriBased(trimmed)
                trimmed.startsWith("hy2://", ignoreCase = true) ||
                trimmed.startsWith("hysteria2://", ignoreCase = true) ||
                trimmed.startsWith("hysteria://", ignoreCase = true) -> parseUriBased(trimmed)
                trimmed.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(trimmed)
                trimmed.startsWith("ssr://", ignoreCase = true) -> parseShadowsocksR(trimmed)
                else -> parseGenericFallback(trimmed)
            }
        } catch (_: Exception) {
            parseGenericFallback(trimmed)
        }
    }

    private fun decodeBase64Safe(input: String): String {
        val clean = input.trim().replace("\n", "").replace("\r", "").replace(" ", "")
        if (clean.isBlank()) return ""
        val padded = when (clean.length % 4) {
            2 -> "$clean=="
            3 -> "$clean="
            else -> clean
        }
        return try {
            String(Base64.decode(padded, Base64.DEFAULT or Base64.NO_WRAP or Base64.URL_SAFE), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun parseVmess(raw: String): ServerTarget? {
        val body = raw.substringAfter("vmess://").substringBefore("#").trim()
        val jsonStr = decodeBase64Safe(body)
        if (jsonStr.isBlank()) return parseGenericFallback(raw)

        return try {
            val json = JSONObject(jsonStr)
            val host = json.optString("add").ifBlank { json.optString("host") }.trim().trim('[', ']')
            val portObj = json.opt("port")
            val port = when (portObj) {
                is Int -> portObj
                is Number -> portObj.toInt()
                is String -> portObj.toIntOrNull() ?: 443
                else -> 443
            }

            if (host.isNotBlank()) ServerTarget(host, if (port in 1..65535) port else 443) else parseGenericFallback(raw)
        } catch (_: Exception) {
            parseGenericFallback(raw)
        }
    }

    private fun parseUriBased(raw: String): ServerTarget? {
        val cleanUrl = raw.substringBefore("#").trim()
        return try {
            val uri = Uri.parse(cleanUrl)
            val host = uri.host?.trim()?.trim('[', ']')
            val port = uri.port

            if (!host.isNullOrBlank()) {
                val validPort = if (port in 1..65535) port else 443
                ServerTarget(host, validPort)
            } else {
                parseGenericFallback(raw)
            }
        } catch (_: Exception) {
            parseGenericFallback(raw)
        }
    }

    private fun parseShadowsocks(raw: String): ServerTarget? {
        val body = raw.substringAfter("ss://").substringBefore("#").trim()
        if (body.isBlank()) return null

        if (body.contains("@")) {
            val hostPortStr = body.substringBefore("?").substringAfter("@")
            val host = hostPortStr.substringBefore(":").trim().trim('[', ']')
            val portStr = hostPortStr.substringAfter(":", "8388").trim()
            val port = portStr.toIntOrNull() ?: 8388
            if (host.isNotBlank()) return ServerTarget(host, port)
        }

        val decoded = decodeBase64Safe(body.substringBefore("?"))
        if (decoded.contains("@")) {
            val hostPortStr = decoded.substringAfter("@")
            val host = hostPortStr.substringBefore(":").trim().trim('[', ']')
            val portStr = hostPortStr.substringAfter(":", "8388").trim()
            val port = portStr.toIntOrNull() ?: 8388
            if (host.isNotBlank()) return ServerTarget(host, port)
        }

        return parseGenericFallback(raw)
    }

    private fun parseShadowsocksR(raw: String): ServerTarget? {
        val body = raw.substringAfter("ssr://").substringBefore("#").trim()
        val decoded = decodeBase64Safe(body)
        if (decoded.isNotBlank()) {
            val parts = decoded.split(":")
            if (parts.size >= 2) {
                val host = parts[0].trim().trim('[', ']')
                val port = parts[1].toIntOrNull() ?: 8388
                if (host.isNotBlank()) return ServerTarget(host, port)
            }
        }
        return parseGenericFallback(raw)
    }

    private fun parseGenericFallback(raw: String): ServerTarget? {
        val atHostPortRegex = Regex("""@([a-zA-Z0-9.-]+|\[[a-fA-F0-9:]+\]):(\d{1,5})""")
        val matchAt = atHostPortRegex.find(raw)
        if (matchAt != null) {
            val host = matchAt.groupValues[1].trim('[').trim(']')
            val port = matchAt.groupValues[2].toIntOrNull() ?: 443
            if (host.isNotBlank()) return ServerTarget(host, port)
        }

        val domainPortRegex = Regex("""([a-zA-Z0-9.-]+\.[a-zA-Z]{2,}):(\d{1,5})""")
        val matchDomain = domainPortRegex.find(raw)
        if (matchDomain != null) {
            val host = matchDomain.groupValues[1]
            val port = matchDomain.groupValues[2].toIntOrNull() ?: 443
            if (host.isNotBlank()) return ServerTarget(host, port)
        }

        val ipPortRegex = Regex("""(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}):(\d{1,5})""")
        val matchIp = ipPortRegex.find(raw)
        if (matchIp != null) {
            val host = matchIp.groupValues[1]
            val port = matchIp.groupValues[2].toIntOrNull() ?: 443
            if (host.isNotBlank()) return ServerTarget(host, port)
        }

        return null
    }

    suspend fun pingServer(target: ServerTarget, timeoutMs: Int = 3000): Long {
        return withContext(Dispatchers.IO) {
            val cleanHost = target.host.trim().trim('[', ']')
            if (cleanHost.isBlank() || target.port !in 1..65535) return@withContext -1L

            val startTime = System.currentTimeMillis()
            try {
                val socket = Socket()
                val socketAddress = InetSocketAddress(cleanHost, target.port)
                socket.connect(socketAddress, timeoutMs)
                val latency = System.currentTimeMillis() - startTime
                try { socket.close() } catch (_: Exception) {}
                if (latency <= 0L) 1L else latency
            } catch (_: Exception) {
                -1L
            }
        }
    }
}
