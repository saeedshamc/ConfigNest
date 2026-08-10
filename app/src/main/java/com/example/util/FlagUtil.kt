package com.example.util

import java.net.URLDecoder
import java.util.Locale

object FlagUtil {

    private val COUNTRY_CODE_TO_FLAG = mapOf(
        "IR" to "🇮🇷", "DE" to "🇩🇪", "US" to "🇺🇸", "FI" to "🇫🇮",
        "NL" to "🇳🇱", "TR" to "🇹🇷", "GB" to "🇬🇧", "UK" to "🇬🇧",
        "FR" to "🇫🇷", "CA" to "🇨🇦", "JP" to "🇯🇵", "SG" to "🇸🇬",
        "RU" to "🇷🇺", "PL" to "🇵🇱", "IT" to "🇮🇹", "ES" to "🇪🇸",
        "SE" to "🇸🇪", "NO" to "🇳🇴", "CH" to "🇨🇭", "AT" to "🇦🇹",
        "KR" to "🇰🇷", "CN" to "🇨🇳", "HK" to "🇭🇰", "TW" to "🇹🇼",
        "AE" to "🇦🇪", "UA" to "🇺🇦", "RO" to "🇷🇴", "IN" to "🇮🇳",
        "AU" to "🇦🇺", "BR" to "🇧🇷", "ZA" to "🇿🇦", "AM" to "🇦🇲",
        "AZ" to "🇦🇿", "GE" to "🇬🇪", "KZ" to "🇰🇿", "UZ" to "🇺🇿"
    )

    private val COUNTRY_NAME_PATTERNS = mapOf(
        "iran" to "🇮🇷", "persia" to "🇮🇷", "germany" to "🇩🇪", "deutsch" to "🇩🇪",
        "united states" to "🇺🇸", "america" to "🇺🇸", "finland" to "🇫🇮",
        "netherlands" to "🇳🇱", "holland" to "🇳🇱", "turkey" to "🇹🇷", "turkiye" to "🇹🇷",
        "united kingdom" to "🇬🇧", "england" to "🇬🇧", "france" to "🇫🇷",
        "canada" to "🇨🇦", "japan" to "🇯🇵", "singapore" to "🇸🇬", "russia" to "🇷🇺"
    )

    fun extractFlagAndTag(rawConfig: String): Pair<String, String> {
        var flag = "🌐"
        var tag = ""

        // Try getting tag after '#'
        val hashIndex = rawConfig.lastIndexOf('#')
        if (hashIndex != -1 && hashIndex < rawConfig.length - 1) {
            val hashTag = rawConfig.substring(hashIndex + 1).trim()
            tag = try {
                URLDecoder.decode(hashTag, "UTF-8")
            } catch (_: Exception) {
                hashTag
            }
        }

        // Check if there is an explicit emoji flag in the tag or config
        val flagRegex = Regex("[\\uD83C][\\uDDE6-\\uDDFF]{2}")
        val match = flagRegex.find(tag.ifEmpty { rawConfig })
        if (match != null) {
            flag = match.value
        } else {
            // Check country codes in tag like [DE], |US|, DE-, etc.
            val upperTag = tag.uppercase(Locale.ROOT)
            for ((code, emoji) in COUNTRY_CODE_TO_FLAG) {
                val patterns = listOf(" $code ", "[$code]", "|$code|", "-$code-", "$code-", "-$code", "_$code", "${code}_")
                if (patterns.any { upperTag.contains(it) } || upperTag.split(" ", "-", "|", "_").contains(code)) {
                    flag = emoji
                    break
                }
            }

            if (flag == "🌐") {
                val lowerTag = tag.lowercase(Locale.ROOT)
                for ((name, emoji) in COUNTRY_NAME_PATTERNS) {
                    if (lowerTag.contains(name)) {
                        flag = emoji
                        break
                    }
                }
            }
        }

        if (tag.isBlank()) {
            val schemeEnd = rawConfig.indexOf("://")
            tag = if (schemeEnd != -1) {
                val clean = rawConfig.substring(schemeEnd + 3)
                clean.take(25)
            } else {
                "Server Config"
            }
        }

        return Pair(flag, tag)
    }
}
