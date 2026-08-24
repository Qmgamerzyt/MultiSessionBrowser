package com.app.browser.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "extensions")
data class ExtensionEntity(
    @PrimaryKey val id: String, // webextension id
    val name: String,
    val version: String,
    val sourceUrl: String?,
    val iconUrl: String?,
    val installedAt: Long,
    val isBuiltIn: Boolean
)