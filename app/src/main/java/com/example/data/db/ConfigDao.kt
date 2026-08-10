package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConfigDao {
    @Query("SELECT * FROM cached_configs ORDER BY fetchedTimestamp DESC")
    fun getAllConfigsFlow(): Flow<List<CachedConfigEntity>>

    @Query("SELECT * FROM cached_configs ORDER BY fetchedTimestamp DESC")
    suspend fun getAllConfigsOnce(): List<CachedConfigEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(configs: List<CachedConfigEntity>)

    @Query("DELETE FROM cached_configs")
    suspend fun clearAll()
}
