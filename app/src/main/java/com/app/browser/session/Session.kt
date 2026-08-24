package com.app.browser.session

import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class Session(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val colorHex: String = "#6200EE",
    val iconKey: String = "default",
    val createdAt: Long = System.currentTimeMillis(),
    val lastActive: Long = System.currentTimeMillis()
) {
    val color: Color
        get() = Color(Color.Companion.getColorInt(colorHex))
    
    companion object {
        fun createDefault(context: android.content.Context): Session {
            return Session(
                name = context.getString(com.app.browser.R.string.app_name),
                colorHex = "#6200EE"
            )
        }
    }
}