package com.app.browser.extension

import android.content.Context
import com.app.browser.engine.EngineProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoRuntime

class JsRunner(
    private val context: Context,
    private val engineProvider: EngineProvider
) {
    
    private val runnerExtensionId = "runner@multisessionbrowser.internal"
    
    // Execute arbitrary JS in the active tab
    suspend fun executeJs(jsCode: String): Result<String> {
        return withContext(Dispatchers.Main) {
            val runtime = engineProvider.getRuntime()
                ?: return@withContext Result.failure(Exception("Engine not initialized"))
            
            val message = """
                {
                    "type": "run_js",
                    "jsCode": ${escapeJson(jsCode)},
                    "requestId": ${System.currentTimeMillis()}
                }
            """.trimIndent()
            
            // Use a completable deferred to wait for the async response
            val deferred = CompletableDeferred<String>()
            
            try {
                runtime.sendNativeMessage(
                    runnerExtensionId,
                    message,
                    object : GeckoRuntime.NativeMessageCallback {
                        override fun onMessage(message: String?) {
                            deferred.complete(message ?: "No response")
                        }
                        
                        override fun onError(error: String?) {
                            deferred.completeExceptionally(Exception(error ?: "Unknown error"))
                        }
                    }
                )
                
                val result = deferred.await()
                Result.success(result)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
    
    // Execute JS from omnibox "javascript:" prefix
    suspend fun executeBookmarklet(jsCode: String): Result<String> {
        // Strip "javascript:" prefix if present
        val code = if (jsCode.startsWith("javascript:")) {
            jsCode.substringAfter("javascript:")
        } else {
            jsCode
        }
        return executeJs(code)
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