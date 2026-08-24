package com.app.browser.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.app.browser.data.entities.SettingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(setting: SettingEntity)

    @Query("SELECT * FROM settings WHERE key = :key")
    suspend fun get(key: String): SettingEntity?

    @Query("SELECT value FROM settings WHERE key = :key")
    suspend fun getValue(key: String): String?

    @Query("SELECT * FROM settings")
    suspend fun getAll(): List<SettingEntity>

    @Query("DELETE FROM settings WHERE key = :key")
    suspend fun delete(key: String)
}