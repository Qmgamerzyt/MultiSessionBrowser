package com.app.browser.omnibox

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class OmniboxViewModel : ViewModel() {
    
    private val _input = MutableStateFlow("")
    val input = _input.asStateFlow()
    
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions = _suggestions.asStateFlow()
    
    private val _showJavaScriptDialog = MutableStateFlow<OmniboxAction.JavaScript?>(null)
    val showJavaScriptDialog = _showJavaScriptDialog.asStateFlow()
    
    fun onTextChanged(text: String) {
        _input.value = text
        // TODO: Generate suggestions from history/bookmarks
        updateSuggestions(text)
    }
    
    fun onSubmit(searchEngineUrl: String): OmniboxAction {
        val action = OmniboxParser.parse(_input.value)
        
        when (action) {
            is OmniboxAction.JavaScript -> {
                _showJavaScriptDialog.value = action
            }
            is OmniboxAction.Search -> {
                val searchUrl = OmniboxParser.getSearchUrl(action.query, searchEngineUrl)
                return OmniboxAction.Navigate(searchUrl)
            }
        }
        return action
    }
    
    fun onJavaScriptDialogResult(runInPage: Boolean): OmniboxAction? {
        val jsAction = _showJavaScriptDialog.value
        _showJavaScriptDialog.value = null
        
        return if (runInPage && jsAction != null) {
            OmniboxAction.RunScript(jsAction.code)
        } else if (!runInPage && jsAction != null) {
            OmniboxAction.Search(jsAction.code)
        } else null
    }
    
    fun dismissJavaScriptDialog() {
        _showJavaScriptDialog.value = null
    }
    
    private fun updateSuggestions(query: String) {
        // TODO: Implement suggestions from history/bookmarks
        _suggestions.value = if (query.length > 2) {
            listOf("Search for $query", "Go to $query")
        } else emptyList()
    }
    
    fun clearInput() {
        _input.value = ""
    }
}