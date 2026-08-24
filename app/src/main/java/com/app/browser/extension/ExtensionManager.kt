package com.app.browser.extension

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.app.browser.data.AppDatabase
import com.app.browser.data.entities.ExtensionEntity
import com.app.browser.engine.EngineProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ExtensionManager(
    private val context: Context,
    private val db: AppDatabase,
    private val engineProvider: EngineProvider,
    private val okHttpClient: OkHttpClient
) {
    
    private val extensionController: WebExtensionController
        get() = engineProvider.webExtensionController
    
    // Initialize built-in extensions
    suspend fun initializeBuiltInExtensions() {
        // Install the runner extension
        try {
            extensionController.ensureBuiltIn(
                Uri.parse("resource://android/assets/runner/"),
                "runner@multisessionbrowser.internal"
            ).thenAccept { result ->
                if (result) {
                    // Runner installed
                }
            }
        } catch (e: Exception) {
            // Ignore if already installed
        }
        
        // Install adblock extension
        try {
            extensionController.ensureBuiltIn(
                Uri.parse("resource://android/assets/adblock/"),
                "adblock@multisessionbrowser.internal"
            ).thenAccept { result ->
                // Adblock installed
            }
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    // Install from AMO URL
    suspend fun installFromAMO(url: String): Result<WebExtension> {
        return withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(url)
                val result = extensionController.install(
                    uri,
                    WebExtensionController.INSTALLATION_METHOD_MANAGER
                ).get() // Blocking for simplicity
                
                // Persist to DB
                val entity = ExtensionEntity(
                    id = result.id,
                    name = result.name ?: "Unknown",
                    version = result.version ?: "1.0",
                    sourceUrl = url,
                    iconUrl = result.iconUrl?.toString(),
                    installedAt = System.currentTimeMillis(),
                    isBuiltIn = false
                )
                db.extensionDao().insert(entity)
                
                Result.success(result)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    // Install from local .xpi file
    suspend fun installFromFile(file: File): Result<WebExtension> {
        return withContext(Dispatchers.IO) {
            try {
                val uri = Uri.fromFile(file)
                val result = extensionController.install(
                    uri,
                    WebExtensionController.INSTALLATION_METHOD_FROM_FILE
                ).get()
                
                val entity = ExtensionEntity(
                    id = result.id,
                    name = result.name ?: "Unknown",
                    version = result.version ?: "1.0",
                    sourceUrl = file.absolutePath,
                    iconUrl = result.iconUrl?.toString(),
                    installedAt = System.currentTimeMillis(),
                    isBuiltIn = false
                )
                db.extensionDao().insert(entity)
                
                Result.success(result)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    // Download and install from .xpi URL
    suspend fun installFromUrl(url: String): Result<WebExtension> {
        return withContext(Dispatchers.IO) {
            try {
                // Download to temp file
                val tempFile = File(context.cacheDir, "extension_${System.currentTimeMillis()}.xpi")
                val request = Request.Builder().url(url).build()
                val response = okHttpClient.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    throw Exception("Failed to download: ${response.code()}")
                }
                
                response.body?.byteStream()?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                
                // Install from file
                val result = installFromFile(tempFile).getOrThrow()
                
                // Cleanup
                tempFile.delete()
                
                Result.success(result)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    // Enable extension for a session
    suspend fun enableForSession(extensionId: String, sessionId: String) {
        val ext = extensionController.list().get().find { it.id == extensionId }
            ?: throw IllegalArgumentException("Extension not found: $extensionId")
        extensionController.enable(ext, WebExtensionController.EnableSource.USER).get()
    }
    
    // Disable extension for a session
    suspend fun disableForSession(extensionId: String, sessionId: String) {
        val ext = extensionController.list().get().find { it.id == extensionId }
            ?: throw IllegalArgumentException("Extension not found: $extensionId")
        extensionController.disable(ext, WebExtensionController.DisableSource.USER).get()
    }
    
    // Uninstall extension completely
    suspend fun uninstall(extensionId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val ext = extensionController.list().get().find { it.id == extensionId }
                    ?: throw IllegalArgumentException("Extension not found: $extensionId")
                extensionController.uninstall(ext).get()
                db.extensionDao().deleteById(extensionId)
                db.matrixDao().deleteByExtensionId(extensionId)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    // Get all installed extensions
    suspend fun getInstalledExtensions(): List<ExtensionEntity> {
        return withContext(Dispatchers.IO) {
            db.extensionDao().getAllOnce()
        }
    }
    
    // Refresh from GeckoView
    suspend fun refreshFromRuntime() {
        val extensions = extensionController.list().get()
        val entities = extensions.map { ext ->
            ExtensionEntity(
                id = ext.id,
                name = ext.name ?: "Unknown",
                version = ext.version ?: "1.0",
                sourceUrl = null,
                iconUrl = ext.iconUrl?.toString(),
                installedAt = System.currentTimeMillis(),
                isBuiltIn = ext.isBuiltIn
            )
        }
        db.extensionDao().insertAll(entities)
    }
}

// Simple Result class
sealed class Result<out T> {
    data class Success<T>(val value: T) : Result<T>()
    data class Failure(val exception: Exception) : Result<Nothing>()
    
    companion object {
        fun <T> success(value: T): Result<T> = Success(value)
        fun <T> failure(exception: Exception): Result<T> = Failure(exception)
    }
    
    fun isSuccess(): Boolean = this is Success<*>
    fun getOrThrow(): T {
        return when (this) {
            is Success -> value
            is Failure -> throw exception
        }
    }
}