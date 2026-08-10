package com.example.util

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.model.ConfigItem
import com.example.model.ProtocolType
import com.example.model.SourceType
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID

object JsonIOUtil {

    private const val TAG = "JsonIOUtil"

    fun exportConfigsToJson(context: Context, uri: Uri, configs: List<ConfigItem>): Boolean {
        return try {
            val jsonArray = JSONArray()
            configs.forEach { item ->
                val obj = JSONObject().apply {
                    put("id", item.id)
                    put("rawConfig", item.rawConfig)
                    put("protocol", item.protocol.name)
                    put("nameTag", item.nameTag)
                    put("countryFlag", item.countryFlag)
                    put("sourceUrl", item.sourceUrl)
                    put("sourceType", item.sourceType.name)
                    put("isMalformed", item.isMalformed)
                    put("warningReason", item.warningReason ?: JSONObject.NULL)
                }
                jsonArray.put(obj)
            }

            val rootObj = JSONObject().apply {
                put("version", 1)
                put("exportedAt", System.currentTimeMillis())
                put("count", configs.size)
                put("configs", jsonArray)
            }

            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(rootObj.toString(2))
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export configs to JSON", e)
            false
        }
    }

    fun importConfigsFromJson(context: Context, uri: Uri): List<ConfigItem> {
        val result = mutableListOf<ConfigItem>()
        try {
            val content = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            } ?: return emptyList()

            val trimmed = content.trim()
            if (trimmed.startsWith("{")) {
                val rootObj = JSONObject(trimmed)
                val jsonArray = when {
                    rootObj.has("configs") -> rootObj.getJSONArray("configs")
                    rootObj.has("items") -> rootObj.getJSONArray("items")
                    rootObj.has("servers") -> rootObj.getJSONArray("servers")
                    else -> null
                }
                if (jsonArray != null) {
                    parseJsonArray(jsonArray, result)
                }
            } else if (trimmed.startsWith("[")) {
                val jsonArray = JSONArray(trimmed)
                parseJsonArray(jsonArray, result)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import configs from JSON", e)
        }
        return result
    }

    private fun parseJsonArray(jsonArray: JSONArray, outList: MutableList<ConfigItem>) {
        for (i in 0 until jsonArray.length()) {
            val item = jsonArray.opt(i) ?: continue
            if (item is JSONObject) {
                val rawConfig = item.optString("rawConfig").ifBlank {
                    item.optString("config").ifBlank {
                        item.optString("url")
                    }
                }.trim()

                if (rawConfig.isNotBlank()) {
                    val protocolStr = item.optString("protocol")
                    val protocol = try {
                        ProtocolType.valueOf(protocolStr)
                    } catch (_: Exception) {
                        ProtocolType.fromConfigString(rawConfig)
                    }

                    val nameTag = item.optString("nameTag").ifBlank {
                        extractNameTagFromConfig(rawConfig)
                    }
                    val countryFlag = item.optString("countryFlag").ifBlank {
                        FlagUtil.extractFlagAndTag(rawConfig).first
                    }
                    val sourceUrl = item.optString("sourceUrl", "Imported Local File")
                    val sourceTypeStr = item.optString("sourceType")
                    val sourceType = try {
                        SourceType.valueOf(sourceTypeStr)
                    } catch (_: Exception) {
                        SourceType.GITHUB
                    }

                    val (isMalformed, warningReason) = ConfigItem.validate(rawConfig, protocol)

                    outList.add(
                        ConfigItem(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            rawConfig = rawConfig,
                            protocol = protocol,
                            nameTag = nameTag,
                            countryFlag = countryFlag,
                            sourceUrl = sourceUrl,
                            sourceType = sourceType,
                            isMalformed = isMalformed,
                            warningReason = warningReason
                        )
                    )
                }
            } else if (item is String) {
                val rawConfig = item.trim()
                if (rawConfig.isNotBlank()) {
                    val protocol = ProtocolType.fromConfigString(rawConfig)
                    val (countryFlag, nameTag) = FlagUtil.extractFlagAndTag(rawConfig)
                    val (isMalformed, warningReason) = ConfigItem.validate(rawConfig, protocol)

                    outList.add(
                        ConfigItem(
                            id = UUID.randomUUID().toString(),
                            rawConfig = rawConfig,
                            protocol = protocol,
                            nameTag = nameTag,
                            countryFlag = countryFlag,
                            sourceUrl = "Imported Local File",
                            sourceType = SourceType.GITHUB,
                            isMalformed = isMalformed,
                            warningReason = warningReason
                        )
                    )
                }
            }
        }
    }

    private fun extractNameTagFromConfig(rawConfig: String): String {
        return try {
            val hashIndex = rawConfig.indexOf('#')
            if (hashIndex != -1 && hashIndex < rawConfig.length - 1) {
                val decoded = java.net.URLDecoder.decode(rawConfig.substring(hashIndex + 1), "UTF-8")
                decoded.trim()
            } else {
                "Imported Config"
            }
        } catch (_: Exception) {
            "Imported Config"
        }
    }
}
