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

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "*/*")
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

    suspend fun fetchAllSources(sources: List<ConfigSource>): FetchResult = withContext(Dispatchers.IO) {
        val enabledSources = sources.filter { it.enabled }
        if (enabledSources.isEmpty()) {
            return@withContext FetchResult(emptyList(), 0, 0)
        }

        val deferredResults = enabledSources.map { source ->
            async {
                fetchSingleSource(source)
            }
        }

        val results = deferredResults.awaitAll()

        var successCount = 0
        var failedCount = 0
        val rawConfigMap = LinkedHashMap<String, Pair<ConfigSource, ProtocolType>>()

        for ((source, extracted) in results) {
            if (extracted != null) {
                successCount++
                for (configStr in extracted) {
                    val trimmed = configStr.trim()
                    if (trimmed.isBlank()) continue

                    val proto = ProtocolType.fromConfigString(trimmed)
                    if (proto != ProtocolType.OTHER && !rawConfigMap.containsKey(trimmed)) {
                        rawConfigMap[trimmed] = Pair(source, proto)
                    }
                }
            } else {
                failedCount++
            }
        }

        val configItems = rawConfigMap.map { (rawConfig, pair) ->
            val (source, protocol) = pair
            val (flag, tag) = FlagUtil.extractFlagAndTag(rawConfig)

            ConfigItem(
                id = rawConfig.hashCode().toString(),
                rawConfig = rawConfig,
                protocol = protocol,
                nameTag = tag,
                countryFlag = flag,
                sourceUrl = source.url,
                sourceType = source.type
            )
        }

        FetchResult(
            configs = configItems,
            successCount = successCount,
            failedCount = failedCount
        )
    }

    private fun fetchSingleSource(source: ConfigSource): List<String>? {
        return try {
            val request = Request.Builder()
                .url(source.url)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bodyText = response.body?.string() ?: return null

                when (source.type) {
                    SourceType.TELEGRAM -> parseTelegramHtml(bodyText)
                    SourceType.GITHUB -> parseGitHubText(bodyText)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseGitHubText(bodyText: String): List<String> {
        val directLines = extractConfigsFromText(bodyText)
        if (directLines.isNotEmpty()) {
            return directLines
        }

        // Try base64 decoding if no direct configs found
        val decoded = tryBase64Decode(bodyText)
        if (!decoded.isNullOrBlank()) {
            return extractConfigsFromText(decoded)
        }

        return emptyList()
    }

    private fun parseTelegramHtml(htmlText: String): List<String> {
        val unescaped = unescapeHtml(htmlText)
        return extractConfigsFromText(unescaped)
    }

    private fun extractConfigsFromText(text: String): List<String> {
        val result = mutableListOf<String>()

        // First try regex matcher to catch configs embedded inside text or HTML
        val matcher = CONFIG_REGEX.matcher(text)
        while (matcher.find()) {
            var match = matcher.group()
            // Strip trailing punctuation if accidentally matched
            match = match.trimEnd('.', ',', ';', ')', ']', '}', '>', '"', '\'')
            if (match.isNotBlank()) {
                result.add(match)
            }
        }

        // If regex didn't catch line-by-line format (e.g. clean line list)
        if (result.isEmpty()) {
            text.lines().forEach { line ->
                val trimmed = line.trim()
                if (PROTOCOL_PREFIXES.any { trimmed.lowercase().startsWith(it) }) {
                    result.add(trimmed)
                }
            }
        }

        return result
    }

    private fun tryBase64Decode(content: String): String? {
        return try {
            val clean = content.replace("\n", "").replace("\r", "").replace(" ", "").trim()
            if (clean.isBlank()) return null
            val bytes = Base64.decode(clean, Base64.DEFAULT or Base64.NO_WRAP or Base64.URL_SAFE)
            String(bytes, Charsets.UTF_8)
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
