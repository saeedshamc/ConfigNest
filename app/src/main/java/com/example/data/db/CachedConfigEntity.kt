package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.model.ConfigItem
import com.example.model.ProtocolType
import com.example.model.SourceType

@Entity(tableName = "cached_configs")
data class CachedConfigEntity(
    @PrimaryKey val id: String,
    val rawConfig: String,
    val protocol: String,
    val nameTag: String,
    val countryFlag: String,
    val sourceUrl: String,
    val sourceType: String,
    val isMalformed: Boolean,
    val warningReason: String?,
    val fetchedTimestamp: Long
) {
    fun toModel(): ConfigItem {
        val proto = try { ProtocolType.valueOf(protocol) } catch (_: Exception) { ProtocolType.OTHER }
        val srcType = try { SourceType.valueOf(sourceType) } catch (_: Exception) { SourceType.GITHUB }
        return ConfigItem(
            id = id,
            rawConfig = rawConfig,
            protocol = proto,
            nameTag = nameTag,
            countryFlag = countryFlag,
            sourceUrl = sourceUrl,
            sourceType = srcType,
            isMalformed = isMalformed,
            warningReason = warningReason
        )
    }

    companion object {
        fun fromModel(item: ConfigItem, timestamp: Long): CachedConfigEntity {
            return CachedConfigEntity(
                id = item.id,
                rawConfig = item.rawConfig,
                protocol = item.protocol.name,
                nameTag = item.nameTag,
                countryFlag = item.countryFlag,
                sourceUrl = item.sourceUrl,
                sourceType = item.sourceType.name,
                isMalformed = item.isMalformed,
                warningReason = item.warningReason,
                fetchedTimestamp = timestamp
            )
        }
    }
}
