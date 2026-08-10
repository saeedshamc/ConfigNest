package com.example.model

enum class SourceType(val displayName: String) {
    GITHUB("GitHub"),
    TELEGRAM("Telegram")
}

data class ConfigSource(
    val id: String,
    val name: String,
    val url: String,
    val type: SourceType,
    val enabled: Boolean = true
)

enum class ProtocolType(val prefix: String, val displayName: String) {
    VMESS("vmess://", "VMess"),
    VLESS("vless://", "VLess"),
    TROJAN("trojan://", "Trojan"),
    SHADOWSOCKS("ss://", "SS"),
    SHADOWSOCKSR("ssr://", "SSR"),
    HYSTERIA2("hysteria2://", "Hysteria2"),
    TUIC("tuic://", "TUIC"),
    OTHER("", "Other");

    companion object {
        fun fromConfigString(config: String): ProtocolType {
            val lower = config.lowercase().trim()
            return when {
                lower.startsWith("vmess://") -> VMESS
                lower.startsWith("vless://") -> VLESS
                lower.startsWith("trojan://") -> TROJAN
                lower.startsWith("ss://") -> SHADOWSOCKS
                lower.startsWith("ssr://") -> SHADOWSOCKSR
                lower.startsWith("hysteria2://") || lower.startsWith("hy2://") -> HYSTERIA2
                lower.startsWith("tuic://") -> TUIC
                else -> OTHER
            }
        }
    }
}

data class ConfigItem(
    val id: String,
    val rawConfig: String,
    val protocol: ProtocolType,
    val nameTag: String,
    val countryFlag: String,
    val sourceUrl: String,
    val sourceType: SourceType
)

enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT
}
