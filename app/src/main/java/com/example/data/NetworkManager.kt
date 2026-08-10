package com.example.data

import android.util.Base64
import android.util.Log
import com.example.model.ConfigSource
import com.example.model.SourceType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object NetworkManager {

    private const val TAG = "NetworkManager"
    private const val MAX_BODY_SIZE = 5 * 1024 * 1024L // 5MB safety cap

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.5")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    private val PROTOCOL_PREFIXES = listOf(
        "vmess://", "vless://", "trojan://", "ss://", "ssr://", "hysteria2://", "hy2://", "hysteria://", "tuic://"
    )

    private val CONFIG_REGEX = Pattern.compile(
        "(?i)(vmess|vless|trojan|ss|ssr|hysteria2|hy2|hysteria|tuic)://[^\\s<>'\"\\n]+",
        Pattern.CASE_INSENSITIVE
    )

    data class NetworkFetchResult(
        val source: ConfigSource,
        val isSuccess: Boolean,
        val httpCode: Int = 0,
        val configs: List<String> = emptyList(),
        val errorMessage: String? = null
    )

    fun fetchSource(source: ConfigSource, proxySettings: com.example.model.ProxySettings? = null): NetworkFetchResult {
        Log.i(TAG, "--------------------------------------------------")
        Log.i(TAG, "[FETCH START] Type=${source.type} Name='${source.name}' URL='${source.url}'")

        val targetClient = if (proxySettings != null && proxySettings.enabled && proxySettings.host.isNotBlank() && proxySettings.port in 1..65535) {
            val pType = if (proxySettings.type == com.example.model.ProxyType.SOCKS) java.net.Proxy.Type.SOCKS else java.net.Proxy.Type.HTTP
            val proxy = java.net.Proxy(pType, java.net.InetSocketAddress(proxySettings.host.trim(), proxySettings.port))
            client.newBuilder().proxy(proxy).build()
        } else {
            client
        }

        val request = try {
            Request.Builder()
                .url(source.url)
                .build()
        } catch (e: Exception) {
            val err = "Invalid URL format for source '${source.name}': ${e.message}"
            Log.e(TAG, "[URL ERROR] $err", e)
            return NetworkFetchResult(source, isSuccess = false, errorMessage = err)
        }

        return try {
            targetClient.newCall(request).execute().use { response ->
                val code = response.code
                val isSuccess = response.isSuccessful
                val contentType = response.header("Content-Type") ?: "unknown"
                val contentLength = response.header("Content-Length") ?: "unknown"

                Log.d(TAG, "[HTTP RESPONSE] URL='${source.url}' Code=$code Success=$isSuccess Type=$contentType Length=$contentLength")

                if (!isSuccess) {
                    val errorMsg = "HTTP $code ${response.message} for URL: ${source.url}"
                    Log.w(TAG, "[HTTP FAILURE] Source='${source.name}' Type=${source.type} Status=$code Message='${response.message}' URL='${source.url}'")
                    return NetworkFetchResult(source, isSuccess = false, httpCode = code, errorMessage = errorMsg)
                }

                val bodyText = readResponseBodySafely(response, source)
                if (bodyText.isNullOrBlank()) {
                    val emptyMsg = "Empty response body received from source '${source.name}' at URL: ${source.url}"
                    Log.w(TAG, "[EMPTY RESPONSE] Source='${source.name}' Type=${source.type} URL='${source.url}'")
                    return NetworkFetchResult(source, isSuccess = true, httpCode = code, configs = emptyList(), errorMessage = emptyMsg)
                }

                Log.d(TAG, "[BODY READ] Source='${source.name}' RawLength=${bodyText.length} chars")

                val parsedConfigs = when (source.type) {
                    SourceType.GITHUB -> parseGitHubSource(bodyText, source)
                    SourceType.TELEGRAM -> parseTelegramSource(bodyText, source)
                }

                if (parsedConfigs.isEmpty()) {
                    Log.w(TAG, "[ZERO CONFIGS] Source='${source.name}' Type=${source.type} fetched successfully but 0 valid V2Ray configs were extracted from URL: ${source.url}")
                } else {
                    Log.i(TAG, "[FETCH SUCCESS] Source='${source.name}' Type=${source.type} Extracted ${parsedConfigs.size} configs from URL: ${source.url}")
                }

                NetworkFetchResult(source, isSuccess = true, httpCode = code, configs = parsedConfigs)
            }
        } catch (e: UnknownHostException) {
            val err = "DNS resolution failed (Unknown Host) for URL '${source.url}': ${e.message}"
            Log.e(TAG, "[DNS FAILURE] Source='${source.name}' URL='${source.url}' - ${e.message}")
            NetworkFetchResult(source, isSuccess = false, errorMessage = err)
        } catch (e: SocketTimeoutException) {
            val err = "Connection/Read timeout for URL '${source.url}': ${e.message}"
            Log.e(TAG, "[TIMEOUT FAILURE] Source='${source.name}' URL='${source.url}' - ${e.message}")
            NetworkFetchResult(source, isSuccess = false, errorMessage = err)
        } catch (e: IOException) {
            val err = "Network I/O error for URL '${source.url}': ${e.javaClass.simpleName} - ${e.message}"
            Log.e(TAG, "[I/O FAILURE] Source='${source.name}' URL='${source.url}' - ${e.message}")
            NetworkFetchResult(source, isSuccess = false, errorMessage = err)
        } catch (e: Exception) {
            val err = "Unexpected error fetching URL '${source.url}': ${e.javaClass.simpleName} - ${e.message}"
            Log.e(TAG, "[UNEXPECTED ERROR] Source='${source.name}' URL='${source.url}'", e)
            NetworkFetchResult(source, isSuccess = false, errorMessage = err)
        }
    }

    private fun readResponseBodySafely(response: Response, source: ConfigSource): String? {
        val responseBody = response.body ?: return null
        return try {
            val sourceStream = responseBody.source()
            sourceStream.request(MAX_BODY_SIZE + 1)
            if (sourceStream.buffer.size > MAX_BODY_SIZE) {
                Log.w(TAG, "[BODY OVERSIZE] Body exceeded 5MB cap for source '${source.name}' at URL '${source.url}'")
                return null
            }
            responseBody.string()
        } catch (e: Exception) {
            Log.e(TAG, "[BODY READ ERROR] Error reading stream for source '${source.name}' at URL '${source.url}': ${e.message}")
            null
        }
    }

    private fun parseGitHubSource(bodyText: String, source: ConfigSource): List<String> {
        val directLines = extractConfigsFromText(bodyText)
        Log.d(TAG, "[GITHUB PARSE DIRECT] Source='${source.name}' Found ${directLines.size} direct protocol configs")

        if (directLines.isNotEmpty()) {
            return directLines
        }

        val decoded = tryBase64Decode(bodyText)
        if (!decoded.isNullOrBlank()) {
            val decodedLines = extractConfigsFromText(decoded)
            Log.d(TAG, "[GITHUB PARSE BASE64] Source='${source.name}' Decoded length=${decoded.length}, Found ${decodedLines.size} configs")
            if (decodedLines.isNotEmpty()) {
                return decodedLines
            }
        }

        Log.w(TAG, "[GITHUB PARSE EMPTY] Source='${source.name}' No configs matched directly or via Base64 at URL: ${source.url}")
        return emptyList()
    }

    private fun parseTelegramSource(htmlText: String, source: ConfigSource): List<String> {
        val resultList = mutableListOf<String>()

        // 1. Direct href extraction from Telegram preview elements
        val hrefRegex = Pattern.compile("href=['\"]((?:vmess|vless|trojan|ss|ssr|hysteria2|hy2|hysteria|tuic)://[^'\"]+)['\"]", Pattern.CASE_INSENSITIVE)
        val matcher = hrefRegex.matcher(htmlText)
        var hrefCount = 0
        while (matcher.find()) {
            val link = matcher.group(1)?.trim()
            if (!link.isNullOrBlank()) {
                resultList.add(link)
                hrefCount++
            }
        }
        Log.d(TAG, "[TELEGRAM PARSE HREF] Source='${source.name}' Extracted $hrefCount href links from HTML preview")

        // 2. Clean HTML markup and extract from message text
        val cleanText = htmlText
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</?(p|div|span|code|pre|a)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), " ")
        val unescaped = unescapeHtml(cleanText)

        val textExtracted = extractConfigsFromText(unescaped)
        Log.d(TAG, "[TELEGRAM PARSE TEXT] Source='${source.name}' Extracted ${textExtracted.size} configs from cleaned message text")

        resultList.addAll(textExtracted)
        val uniqueConfigs = resultList.distinct()
        Log.d(TAG, "[TELEGRAM PARSE TOTAL] Source='${source.name}' Total unique configs=${uniqueConfigs.size} at URL: ${source.url}")
        return uniqueConfigs
    }

    private fun extractConfigsFromText(text: String): List<String> {
        val result = mutableListOf<String>()

        val matcher = CONFIG_REGEX.matcher(text)
        while (matcher.find()) {
            var match = matcher.group()
            if (match != null) {
                match = match.trimEnd('.', ',', ';', ')', ']', '}', '>', '"', '\'', ' ', '\t', '\r')
                if (match.isNotBlank()) {
                    result.add(match)
                }
            }
        }

        if (result.isEmpty()) {
            text.lines().forEach { line ->
                val trimmed = line.trim()
                if (PROTOCOL_PREFIXES.any { trimmed.lowercase().startsWith(it) }) {
                    result.add(trimmed)
                }
            }
        }

        return result.distinct()
    }

    private fun tryBase64Decode(content: String): String? {
        return try {
            var clean = content.replace("\n", "").replace("\r", "").replace(" ", "").trim()
            if (clean.isBlank()) return null

            val missingPadding = clean.length % 4
            if (missingPadding > 0) {
                clean += "=".repeat(4 - missingPadding)
            }

            val flagsToTry = intArrayOf(
                Base64.DEFAULT,
                Base64.NO_WRAP,
                Base64.URL_SAFE,
                Base64.NO_PADDING or Base64.NO_WRAP
            )

            for (flag in flagsToTry) {
                try {
                    val bytes = Base64.decode(clean, flag)
                    val str = String(bytes, Charsets.UTF_8)
                    if (str.isNotBlank() && PROTOCOL_PREFIXES.any { str.contains(it, ignoreCase = true) }) {
                        return str
                    }
                } catch (_: Exception) {}
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun unescapeHtml(input: String): String {
        return input
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#35;", "#")
            .replace("&nbsp;", " ")
            .replace("<br>", "\n")
            .replace("<br/>", "\n")
            .replace("<br />", "\n")
            .replace("</p>", "\n")
    }
}
