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
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
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

    suspend fun fetchAllSources(sources: List<ConfigSource>): FetchResult = withContext(Dispatchers.IO) {
        val enabledSources = sources.filter { it.enabled }
        if (enabledSources.isEmpty()) {
            return@withContext FetchResult(emptyList(), 0, 0)
        }

        val deferredResults = enabledSources.map { source ->
            async {
                val extracted = fetchSingleSource(source)
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

    private fun fetchSingleSource(source: ConfigSource): List<String>? {
        return try {
            val request = Request.Builder()
                .url(source.url)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    android.util.Log.w("ConfigFetcher", "HTTP ${response.code} for source ${source.name} (${source.url})")
                    return null
                }
                val bodyText = response.body?.string() ?: return null

                when (source.type) {
                    SourceType.TELEGRAM -> parseTelegramHtml(bodyText)
                    SourceType.GITHUB -> parseGitHubText(bodyText)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("ConfigFetcher", "Failed fetching ${source.name} (${source.url}): ${e.message}", e)
            null
        }
    }

    private fun parseGitHubText(bodyText: String): List<String> {
        val directLines = extractConfigsFromText(bodyText)
        if (directLines.isNotEmpty()) {
            return directLines
        }

        val decoded = tryBase64Decode(bodyText)
        if (!decoded.isNullOrBlank()) {
            return extractConfigsFromText(decoded)
        }

        return emptyList()
    }

    private fun parseTelegramHtml(htmlText: String): List<String> {
        val cleanText = htmlText
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</?(p|div|span|code|pre)[^>]*>", RegexOption.IGNORE_CASE), "\n")
        val unescaped = unescapeHtml(cleanText)
        return extractConfigsFromText(unescaped)
    }

    private fun extractConfigsFromText(text: String): List<String> {
        val result = mutableListOf<String>()

        val matcher = CONFIG_REGEX.matcher(text)
        while (matcher.find()) {
            var match = matcher.group()
            if (match != null) {
                match = match.trimEnd('.', ',', ';', ')', ']', '}', '>', '"', '\'')
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
