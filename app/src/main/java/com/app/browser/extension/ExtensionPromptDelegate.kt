package com.app.browser.extension

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

class ExtensionPromptDelegate(
    private val activity: Activity,
    private val onResult: (Boolean) -> Unit
) : WebExtensionController.PromptDelegate {
    
    override fun onInstallPrompt(
        extension: WebExtension,
        permissions: List<String>,
        callback: (Boolean) -> Unit
    ) {
        activity.runOnUiThread {
            val dialog = AlertDialog.Builder(activity)
                .setTitle("Install Extension: ${extension.name}")
                .setMessage("This extension requests the following permissions:")
                .setView(createPermissionsView(permissions))
                .setPositiveButton("Install") { _, _ ->
                    callback(true)
                    onResult(true)
                }
                .setNegativeButton("Cancel") { _, _ ->
                    callback(false)
                    onResult(false)
                }
                .create()
            dialog.show()
        }
    }
    
    private fun createPermissionsView(permissions: List<String>): LinearLayout {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        
        permissions.forEach { perm ->
            val checkBox = CheckBox(activity).apply {
                text = formatPermission(perm)
                isChecked = true
                isEnabled = false // Show only, user can't modify individual perms
            }
            layout.addView(checkBox)
        }
        
        return layout
    }
    
    private fun formatPermission(perm: String): String {
        return when (perm) {
            "tabs" -> "Access browser tabs"
            "bookmarks" -> "Access bookmarks"
            "history" -> "Access browsing history"
            "cookies" -> "Access cookies"
            "webNavigation" -> "Monitor navigation"
            "webRequest" -> "Monitor network requests"
            "webRequestBlocking" -> "Block network requests"
            "storage" -> "Access extension storage"
            "nativeMessaging" -> "Communicate with native apps"
            "activeTab" -> "Access active tab"
            "scripting" -> "Inject scripts"
            "<all_urls>" -> "Access all websites"
            else -> perm
        }
    }
}