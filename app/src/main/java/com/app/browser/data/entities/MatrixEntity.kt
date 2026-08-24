package com.app.browser.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "extension_session_matrix",
    primaryKeys = ["extensionId", "sessionId"]
)
data class MatrixEntity(
    val extensionId: String,
    val sessionId: String,
    val state: String // AUTO, OFF, MANUAL
)