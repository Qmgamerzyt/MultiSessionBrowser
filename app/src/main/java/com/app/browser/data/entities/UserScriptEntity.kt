package com.app.browser.data.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "user_scripts",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class UserScriptEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val name: String,
    val code: String,
    val state: String, // AUTO, OFF, MANUAL
    val createdAt: Long,
    val updatedAt: Long
)