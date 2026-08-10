package com.example.util

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
                trimmed.startsWith("hy2://", ignoreCase = true) || trimmed.startsWith("hysteria2://", ignoreCase = true) -> parseUriBased(trimmed)
                trimmed.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(trimmed)
                else -> parseGenericFallback(trimmed)
            }
        } catch (_: Exception) {
            parseGenericFallback(trimmed)
        }
    }

    private fun parseVmess(raw: String): ServerTarget? {
        val base64Part = raw.substringAfter("vmess://").trim()
        val jsonStr = try {
            String(Base64.decode(base64Part, Base64.DEFAULT or Base64.NO_WRAP or Base64.URL_SAFE), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            return parseGenericFallback(raw)
        }

        val json = JSONObject(jsonStr)
        val host = json.optString("add").ifBlank { json.optString("host") }
        val portObj = json.opt("port")
        val port = when (portObj) {
            is Int -> portObj
            is String -> portObj.toIntOrNull() ?: 443
            else -> 443
        }

        return if (host.isNotBlank()) ServerTarget(host, port) else null
    }

    private fun parseUriBased(raw: String): ServerTarget? {
        val regex = Regex("""(?i)^(?:vless|trojan|tuic|hy2|hysteria2|hysteria)://(?:[^@]+@)?([^:/?#]+):(\d+)""")
        val match = regex.find(raw)
        if (match != null) {
            val host = match.groupValues[1]
            val port = match.groupValues[2].toIntOrNull() ?: 443
            return ServerTarget(host, port)
        }
        return parseGenericFallback(raw)
    }

    private fun parseShadowsocks(raw: String): ServerTarget? {
        val body = raw.substringAfter("ss://").substringBefore("#")
        if (body.contains("@")) {
            val hostPort = body.substringAfter("@")
            val host = hostPort.substringBefore(":")
            val portStr = hostPort.substringAfter(":").substringBefore("/")
            val port = portStr.toIntOrNull() ?: 8388
            if (host.isNotBlank()) return ServerTarget(host, port)
        } else {
            try {
                val decoded = String(Base64.decode(body, Base64.DEFAULT or Base64.NO_WRAP or Base64.URL_SAFE), StandardCharsets.UTF_8)
                if (decoded.contains("@")) {
                    val hostPort = decoded.substringAfter("@")
                    val host = hostPort.substringBefore(":")
                    val portStr = hostPort.substringAfter(":").substringBefore("/")
                    val port = portStr.toIntOrNull() ?: 8388
                    if (host.isNotBlank()) return ServerTarget(host, port)
                }
            } catch (_: Exception) {}
        }
        return parseGenericFallback(raw)
    }

    private fun parseGenericFallback(raw: String): ServerTarget? {
        val hostPortRegex = Regex("""@([a-zA-Z0-9.-]+):(\d{1,5})""")
        val match = hostPortRegex.find(raw)
        if (match != null) {
            val host = match.groupValues[1]
            val port = match.groupValues[2].toIntOrNull() ?: 443
            return ServerTarget(host, port)
        }

        val plainRegex = Regex("""([a-zA-Z0-9.-]+\.[a-zA-Z]{2,}):(\d{1,5})""")
        val matchPlain = plainRegex.find(raw)
        if (matchPlain != null) {
            val host = matchPlain.groupValues[1]
            val port = matchPlain.groupValues[2].toIntOrNull() ?: 443
            return ServerTarget(host, port)
        }

        return null
    }

    suspend fun pingServer(target: ServerTarget, timeoutMs: Int = 2500): Long {
        return withContext(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(target.host, target.port), timeoutMs)
                val latency = System.currentTimeMillis() - startTime
                socket.close()
                if (latency == 0L) 1L else latency
            } catch (_: Exception) {
                -1L
            }
        }
    }
}
