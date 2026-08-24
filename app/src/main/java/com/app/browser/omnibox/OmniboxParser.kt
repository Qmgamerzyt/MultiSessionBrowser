package com.app.browser.omnibox

sealed class OmniboxAction {
    data class Navigate(val url: String) : OmniboxAction()
    data class Search(val query: String) : OmniboxAction()
    data class JavaScript(val code: String) : OmniboxAction()
    data class RunScript(val scriptId: String) : OmniboxAction()
}

object OmniboxParser {
    
    private val URL_PATTERN = "^(https?://|file://|about:|chrome:|data:|javascript:)".toRegex()
    private val DOMAIN_PATTERN = "^[a-zA-Z0-9][a-zA-Z0-9.-]*\\.[a-zA-Z]{2,}(/.*)?$".toRegex()
    
    fun parse(input: String): OmniboxAction {
        val trimmed = input.trim()
        
        // Check for javascript: prefix
        if (trimmed.startsWith("javascript:", ignoreCase = true)) {
            val code = trimmed.substringAfter("javascript:")
            return OmniboxAction.JavaScript(code)
        }
        
        // Check for URL pattern
        if (URL_PATTERN.matches(trimmed)) {
            return OmniboxAction.Navigate(normalizeUrl(trimmed))
        }
        
        // Check if it looks like a domain
        if (DOMAIN_PATTERN.matches(trimmed)) {
            return OmniboxAction.Navigate("https://$trimmed")
        }
        
        // Check for IP address
        if (trimmed.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+(?:\\:\\d+)?(/.*)?$".toRegex())) {
            return OmniboxAction.Navigate("http://$trimmed")
        }
        
        // Check for localhost
        if (trimmed.startsWith("localhost") || trimmed.startsWith("127.0.0.1")) {
            return OmniboxAction.Navigate("http://$trimmed")
        }
        
        // Default to search
        return OmniboxAction.Search(trimmed)
    }
    
    private fun normalizeUrl(url: String): String {
        return if (url.startsWith("javascript:", ignoreCase = true)) {
            url // Keep javascript: as-is for special handling
        } else if (url.startsWith("http://") || url.startsWith("https://") 
            || url.startsWith("file://") || url.startsWith("about:")
            || url.startsWith("chrome:") || url.startsWith("data:")) {
            url
        } else {
            "https://$url"
        }
    }
    
    // Get search URL for query
    fun getSearchUrl(query: String, searchEngineUrl: String): String {
        val encoded = android.net.Uri.encode(query)
        return searchEngineUrl.replace("%s", encoded)
    }
}