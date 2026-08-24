package com.app.browser.engine

import android.content.Context
import java.io.File

class ProfileManager(private val context: Context) {
    
    private val profilesBaseDir: File by lazy {
        File(context.filesDir, "profiles").apply { mkdirs() }
    }
    
    fun getProfileDir(sessionId: String): String {
        val dir = File(profilesBaseDir, "session_$sessionId")
        dir.mkdirs()
        return dir.absolutePath
    }
    
    fun deleteProfileDir(sessionId: String) {
        val dir = File(profilesBaseDir, "session_$sessionId")
        if (dir.exists()) {
            deleteRecursively(dir)
        }
    }
    
    private fun deleteRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        file.delete()
    }
    
    fun getAllProfileDirs(): List<File> {
        return profilesBaseDir.listFiles()?.filter { it.isDirectory }?.toList() ?: emptyList()
    }
}