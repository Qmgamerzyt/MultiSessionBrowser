package com.app.browser.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorHex: String,
    val iconKey: String,
    val createdAt: Long,
    val lastActive: Long
)