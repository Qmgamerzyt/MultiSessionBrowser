package com.app.browser.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val url: String,
    val fileName: String,
    val filePath: String?,
    val status: String, // PENDING, DOWNLOADING, COMPLETED, FAILED, CANCELLED
    val progress: Float,
    val totalBytes: Long,
    val downloadedBytes: Long,
    val createdAt: Long,
    val completedAt: Long?
)