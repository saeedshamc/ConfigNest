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
            val hostRaw = json.optString("add").ifBlank { json.optString("host") }
            val host = hostRaw.trim().trim('[', ']')
            val portObj = json.opt("port")
            val port = when (portObj) {
                is Int -> portObj
                is Number -> portObj.toInt()
                is String -> portObj.substringBefore("/").substringBefore("?").trim().toIntOrNull() ?: 443
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
            
            val rawPort = try {
                val p = uri.port
                if (p in 1..65535) p else null
            } catch (_: Exception) {
                null
            }

            val port = rawPort ?: run {
                val authority = uri.encodedAuthority ?: uri.authority ?: ""
                val hostPort = if (authority.contains("@")) authority.substringAfter("@") else authority
                if (hostPort.contains(":")) {
                    val portPart = hostPort.substringAfter(":").substringBefore("?").substringBefore("/").trim()
                    portPart.toIntOrNull()?.takeIf { it in 1..65535 }
                } else null
            } ?: 443

            if (!host.isNullOrBlank()) {
                ServerTarget(host, port)
            } else {
                val withoutScheme = cleanUrl.substringAfter("://").trim()
                val authority = withoutScheme.substringBefore("?").substringBefore("/").trim()
                val hostPortPart = if (authority.contains("@")) authority.substringAfter("@") else authority
                if (hostPortPart.isNotBlank()) {
                    val fallbackHost: String
                    val fallbackPort: Int
                    if (hostPortPart.startsWith("[")) {
                        fallbackHost = hostPortPart.substringBefore("]").substringAfter("[").trim()
                        val afterBracket = hostPortPart.substringAfter("]", "")
                        val portStr = if (afterBracket.startsWith(":")) afterBracket.substringAfter(":").substringBefore("/").substringBefore("?") else ""
                        fallbackPort = portStr.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 443
                    } else if (hostPortPart.contains(":")) {
                        fallbackHost = hostPortPart.substringBefore(":").trim()
                        fallbackPort = hostPortPart.substringAfter(":").substringBefore("/").substringBefore("?").trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 443
                    } else {
                        fallbackHost = hostPortPart.trim()
                        fallbackPort = 443
                    }
                    if (fallbackHost.isNotBlank()) {
                        ServerTarget(fallbackHost, fallbackPort)
                    } else {
                        parseGenericFallback(raw)
                    }
                } else {
                    parseGenericFallback(raw)
                }
            }
        } catch (_: Exception) {
            parseGenericFallback(raw)
        }
    }

    private fun parseShadowsocks(raw: String): ServerTarget? {
        val body = raw.substringAfter("ss://").substringBefore("#").trim()
        if (body.isBlank()) return null

        if (body.contains("@")) {
            val userPortStr = body.substringBefore("?")
            val hostPortStr = userPortStr.substringAfter("@")
            val host = hostPortStr.substringBefore(":").substringBefore("/").trim().trim('[', ']')
            val portStr = hostPortStr.substringAfter(":", "").substringBefore("/").substringBefore("?").trim()
            val port = portStr.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8388
            if (host.isNotBlank()) return ServerTarget(host, port)
        }

        val decoded = decodeBase64Safe(body.substringBefore("?"))
        if (decoded.contains("@")) {
            val hostPortStr = decoded.substringAfter("@")
            val host = hostPortStr.substringBefore(":").substringBefore("/").trim().trim('[', ']')
            val portStr = hostPortStr.substringAfter(":", "").substringBefore("/").substringBefore("?").trim()
            val port = portStr.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8388
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
                val portStr = parts[1].substringBefore("/").substringBefore("?").trim()
                val port = portStr.toIntOrNull()?.takeIf { it in 1..65535 } ?: 8388
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
            val port = matchAt.groupValues[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: 443
            if (host.isNotBlank()) return ServerTarget(host, port)
        }

        val ipPortRegex = Regex("""(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}):(\d{1,5})""")
        val matchIp = ipPortRegex.find(raw)
        if (matchIp != null) {
            val host = matchIp.groupValues[1]
            val port = matchIp.groupValues[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: 443
            if (host.isNotBlank()) return ServerTarget(host, port)
        }

        val domainPortRegex = Regex("""([a-zA-Z0-9.-]+\.[a-zA-Z]{2,}):(\d{1,5})""")
        val matchDomain = domainPortRegex.find(raw)
        if (matchDomain != null) {
            val host = matchDomain.groupValues[1]
            val port = matchDomain.groupValues[2].toIntOrNull()?.takeIf { it in 1..65535 } ?: 443
            if (host.isNotBlank()) return ServerTarget(host, port)
        }

        return null
    }

    suspend fun pingServer(
        target: ServerTarget,
        timeoutMs: Int = 3000,
        proxySettings: com.example.model.ProxySettings? = null
    ): Long {
        return withContext(Dispatchers.IO) {
            val cleanHost = target.host.trim().trim('[', ']')
            if (cleanHost.isBlank() || target.port !in 1..65535) return@withContext -1L

            try {
                val socket = if (proxySettings != null && proxySettings.enabled && proxySettings.host.isNotBlank() && proxySettings.port in 1..65535) {
                    val pType = if (proxySettings.type == com.example.model.ProxyType.SOCKS) java.net.Proxy.Type.SOCKS else java.net.Proxy.Type.HTTP
                    val proxy = java.net.Proxy(pType, InetSocketAddress(proxySettings.host.trim(), proxySettings.port))
                    Socket(proxy)
                } else {
                    Socket()
                }

                val socketAddress = if (proxySettings != null && proxySettings.enabled) {
                    InetSocketAddress.createUnresolved(cleanHost, target.port)
                } else {
                    InetSocketAddress(cleanHost, target.port)
                }

                if (socketAddress.isUnresolved && (proxySettings == null || !proxySettings.enabled)) {
                    return@withContext -1L
                }

                val startTime = System.nanoTime()
                try {
                    socket.connect(socketAddress, timeoutMs)
                    val endTime = System.nanoTime()
                    val latency = (endTime - startTime) / 1_000_000L
                    if (latency <= 0L) 1L else latency
                } finally {
                    try { socket.close() } catch (_: Exception) {}
                }
            } catch (_: Exception) {
                -1L
            }
        }
    }
}

