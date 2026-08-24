package com.app.browser.extension

import android.content.Context
import com.app.browser.engine.EngineProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtensionController

class ManualRunner(
    private val context: Context,
    private val engineProvider: EngineProvider
) {
    
    private val runnerExtensionId = "runner@multisessionbrowser.internal"
    
    // Run a MANUAL script in the active tab
    suspend fun runScript(scriptCode: String): Result<String> {
        return withContext(Dispatchers.Main) {
            val runtime = engineProvider.getRuntime()
                ?: return@withContext Result.failure(Exception("Engine not initialized"))
            
            // Send message to runner extension via native messaging
            val message = """
                {
                    "type": "run_manual",
                    "scriptCode": ${escapeJson(scriptCode)},
                    "requestId": ${System.currentTimeMillis()}
                }
            """.trimIndent()
            
            try {
                runtime.sendNativeMessage(
                    runnerExtensionId,
                    message,
                    object : GeckoRuntime.NativeMessageCallback {
                        override fun onMessage(message: String?) {
                            // Handle response
                        }
                        
                        override fun onError(error: String?) {
                            // Handle error
                        }
                    }
                )
                Result.success("Script triggered")
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    // Run MANUAL extension by connecting to it
    suspend fun runManualExtension(extensionId: String, scriptCode: String): Result<String> {
        return withContext(Dispatchers.Main) {
            val runtime = engineProvider.getRuntime()
                ?: return@withContext Result.failure(Exception("Engine not initialized"))
            
            // For MANUAL extensions, we send a native message to the runner
            // which then forwards to the extension via native messaging
            val message = """
                {
                    "type": "run_manual_extension",
                    "extensionId": "$extensionId",
                    "scriptCode": ${escapeJson(scriptCode)},
                    "requestId": ${System.currentTimeMillis()}
                }
            """.trimIndent()
            
            try {
                runtime.sendNativeMessage(
                    runnerExtensionId,
                    message,
                    object : GeckoRuntime.NativeMessageCallback {
                        override fun onMessage(message: String?) {
                            // Response handled
                        }
                        
                        override fun onError(error: String?) {
                            // Error handled
                        }
                    }
                )
                Result.success("Extension triggered")
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    private fun escapeJson(str: String): String {
        return str
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}