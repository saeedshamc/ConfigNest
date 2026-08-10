package com.example.data

import android.util.Base64
import com.example.model.ConfigItem
import com.example.model.ConfigSource
import com.example.model.ProtocolType
import com.example.model.SourceType
import com.example.util.FlagUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class FetchResult(
    val configs: List<ConfigItem>,
    val successCount: Int,
    val failedCount: Int
)

class ConfigFetcherRepository {

    companion object {
        private const val MAX_BODY_SIZE = 5 * 1024 * 1024L // 5MB limit to prevent OOM
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,text/plain;q=0.8,*/*;q=0.5")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    private val PROTOCOL_PREFIXES = listOf(
        "vmess://", "vless://", "trojan://", "ss://", "ssr://", "hysteria2://", "hy2://", "tuic://"
    )

    private val CONFIG_REGEX = Pattern.compile(
        "(?i)(vmess|vless|trojan|ss|ssr|hysteria2|hy2|tuic)://[^\\s<>'\"\\n]+",
        Pattern.CASE_INSENSITIVE
    )

    suspend fun fetchAllSources(
        sources: List<ConfigSource>,
        proxySettings: com.example.model.ProxySettings? = null
    ): FetchResult = withContext(Dispatchers.IO) {
        val enabledSources = sources.filter { it.enabled }
        if (enabledSources.isEmpty()) {
            return@withContext FetchResult(emptyList(), 0, 0)
        }

        val deferredResults = enabledSources.map { source ->
            async {
                val extracted = fetchSingleSource(source, proxySettings)
                Pair(source, extracted)
            }
        }

        val results = deferredResults.awaitAll()

        var successCount = 0
        var failedCount = 0
        val rawConfigMap = LinkedHashMap<String, Pair<ConfigSource, ProtocolType>>()

        for (pair in results) {
            val source = pair.first
            val extracted = pair.second
            if (extracted != null) {
                successCount++
                for (configStr in extracted) {
                    val trimmed = configStr.trim()
                    if (trimmed.isBlank()) continue

                    val proto = ProtocolType.fromConfigString(trimmed)
                    if (!rawConfigMap.containsKey(trimmed)) {
                        rawConfigMap[trimmed] = Pair(source, proto)
                    }
                }
            } else {
                failedCount++
            }
        }

        val configItems = rawConfigMap.map { (rawConfig, pair) ->
            val source = pair.first
            val protocol = pair.second
            val (flag, tag) = FlagUtil.extractFlagAndTag(rawConfig)
            val (isMalformed, warningReason) = ConfigItem.validate(rawConfig, protocol)

            ConfigItem(
                id = rawConfig.hashCode().toString(),
                rawConfig = rawConfig,
                protocol = protocol,
                nameTag = tag,
                countryFlag = flag,
                sourceUrl = source.url,
                sourceType = source.type,
                isMalformed = isMalformed,
                warningReason = warningReason
            )
        }

        FetchResult(
            configs = configItems,
            successCount = successCount,
            failedCount = failedCount
        )
    }

    fun parseConfigsFromClipboardText(text: String, sourceName: String = "Clipboard Import"): List<ConfigItem> {
        val directExtracted = extractConfigsFromText(text)
        val decodedExtracted = if (directExtracted.isEmpty()) {
            tryBase64Decode(text)?.let { extractConfigsFromText(it) } ?: emptyList()
        } else {
            emptyList()
        }

        val allRaw = (directExtracted + decodedExtracted).distinct()
        return allRaw.map { rawConfig ->
            val protocol = ProtocolType.fromConfigString(rawConfig)
            val (flag, tag) = FlagUtil.extractFlagAndTag(rawConfig)
            val (isMalformed, warningReason) = ConfigItem.validate(rawConfig, protocol)

            ConfigItem(
                id = rawConfig.hashCode().toString(),
                rawConfig = rawConfig,
                protocol = protocol,
                nameTag = tag,
                countryFlag = flag,
                sourceUrl = sourceName,
                sourceType = SourceType.GITHUB,
                isMalformed = isMalformed,
                warningReason = warningReason
            )
        }
    }

    private fun fetchSingleSource(
        source: ConfigSource,
        proxySettings: com.example.model.ProxySettings? = null
    ): List<String>? {
        val result = NetworkManager.fetchSource(source, proxySettings)
        return if (result.isSuccess) {
            result.configs
        } else {
            null
        }
    }

    private fun readResponseBodySafely(response: okhttp3.Response, sourceName: String): String? {
        val responseBody = response.body ?: return null
        val contentLength = responseBody.contentLength()
        if (contentLength > MAX_BODY_SIZE) {
            android.util.Log.w("ConfigFetcher", "Content-Length ($contentLength bytes) exceeds 5MB limit for '$sourceName'")
            return null
        }

        return try {
            val source = responseBody.source()
            source.request(MAX_BODY_SIZE + 1)
            if (source.buffer.size > MAX_BODY_SIZE) {
                android.util.Log.w("ConfigFetcher", "Response body exceeds 5MB limit for '$sourceName'")
                return null
            }
            responseBody.string()
        } catch (e: Exception) {
            android.util.Log.e("ConfigFetcher", "Error reading response body for '$sourceName': ${e.message}")
            null
        }
    }

    private fun parseGitHubText(bodyText: String, source: ConfigSource): List<String> {
        val directLines = extractConfigsFromText(bodyText)
        if (directLines.isNotEmpty()) {
            android.util.Log.d("ConfigFetcher", "GitHub source '${source.name}': extracted ${directLines.size} direct configs")
            return directLines
        }

        val decoded = tryBase64Decode(bodyText)
        if (!decoded.isNullOrBlank()) {
            val decodedLines = extractConfigsFromText(decoded)
            if (decodedLines.isNotEmpty()) {
                android.util.Log.d("ConfigFetcher", "GitHub source '${source.name}': extracted ${decodedLines.size} configs after Base64 decoding")
                return decodedLines
            }
        }

        android.util.Log.w("ConfigFetcher", "GitHub source '${source.name}': zero configs found in raw body or decoded base64 content")
        return emptyList()
    }

    private fun parseTelegramHtml(htmlText: String, source: ConfigSource): List<String> {
        val resultList = mutableListOf<String>()

        // 1. Direct href extraction for links in Telegram HTML elements
        val hrefRegex = Pattern.compile("href=['\"]((?:vmess|vless|trojan|ss|ssr|hysteria2|hy2|tuic)://[^'\"]+)['\"]", Pattern.CASE_INSENSITIVE)
        val matcher = hrefRegex.matcher(htmlText)
        while (matcher.find()) {
            val link = matcher.group(1)?.trim()
            if (!link.isNullOrBlank()) {
                resultList.add(link)
            }
        }

        // 2. Clean HTML tags and parse remaining message body
        val cleanText = htmlText
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</?(p|div|span|code|pre|a)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), " ")
        val unescaped = unescapeHtml(cleanText)

        val textExtracted = extractConfigsFromText(unescaped)
        resultList.addAll(textExtracted)

        val uniqueConfigs = resultList.distinct()
        android.util.Log.d("ConfigFetcher", "Telegram source '${source.name}': extracted ${uniqueConfigs.size} unique configs from HTML")
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

            // Add missing padding if needed
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
                } catch (_: Exception) {
                    // Try next flag
                }
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
