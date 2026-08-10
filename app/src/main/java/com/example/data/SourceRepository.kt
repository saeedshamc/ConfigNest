package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.ConfigItem
import com.example.model.ConfigSource
import com.example.model.ProtocolType
import com.example.model.SourceType
import com.example.model.ThemeMode
import com.example.util.FlagUtil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "config_fetcher_prefs")

class SourceRepository(private val context: Context) {

    private val SOURCES_KEY = stringPreferencesKey("sources_json_v1")
    private val CACHED_CONFIGS_KEY = stringPreferencesKey("cached_configs_json_v1")
    private val LAST_UPDATED_KEY = longPreferencesKey("last_updated_time")
    private val THEME_KEY = stringPreferencesKey("app_theme_mode")

    private val configDao = com.example.data.db.AppDatabase.getDatabase(context).configDao()

    val sourcesFlow: Flow<List<ConfigSource>> = context.dataStore.data.map { prefs ->
        val raw = prefs[SOURCES_KEY]
        if (raw.isNullOrBlank()) {
            DefaultSources.LIST
        } else {
            deserializeSources(raw)
        }
    }

    val cachedConfigsFlow: Flow<Pair<List<ConfigItem>, Long>> = kotlinx.coroutines.flow.combine(
        configDao.getAllConfigsFlow(),
        context.dataStore.data
    ) { entities, prefs ->
        val time = prefs[LAST_UPDATED_KEY] ?: 0L
        Pair(entities.map { it.toModel() }, time)
    }

    val themeModeFlow: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        val modeStr = prefs[THEME_KEY] ?: ThemeMode.SYSTEM.name
        try {
            ThemeMode.valueOf(modeStr)
        } catch (_: Exception) {
            ThemeMode.SYSTEM
        }
    }

    suspend fun saveSources(sources: List<ConfigSource>) {
        val json = serializeSources(sources)
        context.dataStore.edit { prefs ->
            prefs[SOURCES_KEY] = json
        }
    }

    suspend fun saveCachedConfigs(configs: List<ConfigItem>, timestamp: Long) {
        configDao.clearAll()
        val entities = configs.map { com.example.data.db.CachedConfigEntity.fromModel(it, timestamp) }
        configDao.insertAll(entities)
        context.dataStore.edit { prefs ->
            prefs[LAST_UPDATED_KEY] = timestamp
        }
    }

    suspend fun saveThemeMode(mode: ThemeMode) {
        context.dataStore.edit { prefs ->
            prefs[THEME_KEY] = mode.name
        }
    }

    suspend fun resetToDefaultSources() {
        saveSources(DefaultSources.LIST)
    }

    suspend fun addSource(inputUrl: String, customName: String?, explicitType: SourceType?): ConfigSource {
        val (normalizedUrl, detectedType) = normalizeSourceUrlAndType(inputUrl, explicitType)
        val name = customName?.ifBlank { null } ?: when (detectedType) {
            SourceType.TELEGRAM -> {
                val channel = normalizedUrl.removePrefix("https://t.me/s/").removeSuffix("/")
                "Telegram @$channel"
            }
            SourceType.GITHUB -> {
                val lastPart = normalizedUrl.substringAfterLast("/")
                if (lastPart.isNotBlank()) lastPart else "Custom Source"
            }
        }

        val newSource = ConfigSource(
            id = UUID.randomUUID().toString(),
            name = name,
            url = normalizedUrl,
            type = detectedType,
            enabled = true
        )

        val currentRaw = context.dataStore.data.map { it[SOURCES_KEY] ?: "" }.first()
        val current = deserializeSources(currentRaw).toMutableList()

        current.add(newSource)
        saveSources(current)
        return newSource
    }

    fun normalizeSourceUrlAndType(urlInput: String, explicitType: SourceType?): Pair<String, SourceType> {
        val trimmed = urlInput.trim()
        val isTelegram = explicitType == SourceType.TELEGRAM ||
                trimmed.lowercase().contains("t.me") ||
                trimmed.startsWith("@") ||
                (!trimmed.startsWith("http") && !trimmed.contains("/"))

        if (isTelegram) {
            var channel = trimmed
                .removePrefix("https://")
                .removePrefix("http://")
                .removePrefix("t.me/s/")
                .removePrefix("t.me/")
                .removePrefix("@")
                .trim()
            if (channel.contains("/")) channel = channel.substringBefore("/")
            if (channel.contains("?")) channel = channel.substringBefore("?")

            val cleanUrl = "https://t.me/s/$channel"
            return Pair(cleanUrl, SourceType.TELEGRAM)
        }

        var cleanUrl = trimmed
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            cleanUrl = "https://$cleanUrl"
        }
        return Pair(cleanUrl, SourceType.GITHUB)
    }

    private fun serializeSources(sources: List<ConfigSource>): String {
        val array = JSONArray()
        sources.forEach { item ->
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("name", item.name)
            obj.put("url", item.url)
            obj.put("type", item.type.name)
            obj.put("enabled", item.enabled)
            array.put(obj)
        }
        return array.toString()
    }

    private fun deserializeSources(jsonStr: String): List<ConfigSource> {
        if (jsonStr.isBlank()) return DefaultSources.LIST
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<ConfigSource>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val type = try {
                    SourceType.valueOf(obj.getString("type"))
                } catch (_: Exception) {
                    SourceType.GITHUB
                }
                list.add(
                    ConfigSource(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        name = obj.optString("name", "Source"),
                        url = obj.optString("url", ""),
                        type = type,
                        enabled = obj.optBoolean("enabled", true)
                    )
                )
            }
            if (list.isEmpty()) DefaultSources.LIST else list
        } catch (_: Exception) {
            DefaultSources.LIST
        }
    }

    private fun serializeConfigs(configs: List<ConfigItem>): String {
        val array = JSONArray()
        configs.forEach { item ->
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("rawConfig", item.rawConfig)
            obj.put("protocol", item.protocol.name)
            obj.put("nameTag", item.nameTag)
            obj.put("countryFlag", item.countryFlag)
            obj.put("sourceUrl", item.sourceUrl)
            obj.put("sourceType", item.sourceType.name)
            obj.put("isMalformed", item.isMalformed)
            obj.put("warningReason", item.warningReason ?: "")
            array.put(obj)
        }
        return array.toString()
    }

    private fun deserializeConfigs(jsonStr: String): List<ConfigItem> {
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<ConfigItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val rawConfig = obj.optString("rawConfig", "")
                if (rawConfig.isBlank()) continue
                val protocol = try {
                    ProtocolType.valueOf(obj.optString("protocol", ProtocolType.fromConfigString(rawConfig).name))
                } catch (_: Exception) {
                    ProtocolType.fromConfigString(rawConfig)
                }
                val sourceType = try {
                    SourceType.valueOf(obj.optString("sourceType", SourceType.GITHUB.name))
                } catch (_: Exception) {
                    SourceType.GITHUB
                }

                val (flag, tag) = FlagUtil.extractFlagAndTag(rawConfig)
                val (isMalformed, warningReason) = ConfigItem.validate(rawConfig, protocol)

                list.add(
                    ConfigItem(
                        id = obj.optString("id", rawConfig.hashCode().toString()),
                        rawConfig = rawConfig,
                        protocol = protocol,
                        nameTag = obj.optString("nameTag", tag),
                        countryFlag = obj.optString("countryFlag", flag),
                        sourceUrl = obj.optString("sourceUrl", ""),
                        sourceType = sourceType,
                        isMalformed = obj.optBoolean("isMalformed", isMalformed),
                        warningReason = obj.optString("warningReason").ifEmpty { warningReason }
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }
}
