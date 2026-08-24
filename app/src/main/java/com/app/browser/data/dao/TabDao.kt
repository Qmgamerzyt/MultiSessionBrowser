package com.app.browser.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.app.browser.data.entities.TabEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TabDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tab: TabEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tabs: List<TabEntity>)

    @Update
    suspend fun update(tab: TabEntity)

    @Query("DELETE FROM tabs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM tabs WHERE sessionId = :sessionId")
    suspend fun deleteBySessionId(sessionId: String)

    @Query("SELECT * FROM tabs WHERE sessionId = :sessionId ORDER BY orderIndex ASC")
    fun getBySessionId(sessionId: String): Flow<List<TabEntity>>

    @Query("SELECT * FROM tabs WHERE sessionId = :sessionId ORDER BY orderIndex ASC")
    suspend fun getBySessionIdOnce(sessionId: String): List<TabEntity>

    @Query("SELECT * FROM tabs WHERE id = :id")
    suspend fun getById(id: String): TabEntity?

    @Query("SELECT * FROM tabs WHERE id = :id")
    fun observeById(id: String): Flow<TabEntity?>

    @Query("UPDATE tabs SET title = :title, url = :url, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateTab(id: String, title: String, url: String, updatedAt: Long)

    @Query("UPDATE tabs SET orderIndex = :orderIndex WHERE id = :id")
    suspend fun updateOrder(id: String, orderIndex: Int)
}