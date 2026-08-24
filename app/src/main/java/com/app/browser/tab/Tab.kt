package com.app.browser.tab

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Tab(
    val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    var url: String = "about:blank",
    var title: String = "New Tab",
    val orderIndex: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)