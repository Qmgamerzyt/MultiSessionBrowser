package app.multisession.browser.ui.browser

import android.content.Context
import android.widget.ListPopupWindow
import android.widget.ListView
import app.multisession.browser.R
import app.multisession.browser.core.Prefs
import app.multisession.browser.core.UrlUtils
import app.multisession.browser.data.BrowserRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SuggestionPopup(
    private val context: Context,
    private val repo: BrowserRepository,
    private val scope: CoroutineScope,
    private val onItemClick: (SuggestionItem) -> Unit
) {
    private val adapter = SuggestionAdapter(context)
    private val popup = ListPopupWindow(context)
    private var searchJob: Job? = null

    init {
        popup.anchorView = null // set later via anchor()
        popup.setAdapter(adapter)
        popup.isModal = true
        popup.width = ListPopupWindow.MATCH_PARENT
        popup.setOnItemClickListener { _, _, position, _ ->
            val item = adapter.getItem(position)
            popup.dismiss()
            onItemClick(item)
        }
    }

    fun anchor(view: android.view.View) {
        popup.anchorView = view
    }

    fun showSuggestions(input: String, sessionId: String) {
        searchJob?.cancel()
        if (input.isBlank()) {
            adapter.clear()
            popup.dismiss()
            return
        }
        searchJob = scope.launch {
            val suggestions = generateSuggestions(input, sessionId)
            withContext(Dispatchers.Main) {
                if (suggestions.isNotEmpty()) {
                    adapter.update(suggestions)
                    if (!popup.isShowing) {
                        popup.show()
                    }
                } else {
                    adapter.clear()
                    popup.dismiss()
                }
            }
        }
    }

    fun dismiss() {
        if (popup.isShowing) popup.dismiss()
        adapter.clear()
    }

    val isShowing: Boolean get() = popup.isShowing

    private suspend fun generateSuggestions(input: String, sessionId: String): List<SuggestionItem> {
        val suggestions = mutableListOf<SuggestionItem>()

        suggestions.add(SuggestionItem(
            SuggestionItem.Type.SEARCH,
            context.getString(R.string.suggestion_search, input),
            Prefs.searchUrlFor(input),
            R.drawable.ic_search
        ))

        if (UrlUtils.looksLikeHost(input)) {
            val url = if (input.startsWith("http")) input else "https://$input"
            suggestions.add(SuggestionItem(
                SuggestionItem.Type.NAVIGATE,
                context.getString(R.string.suggestion_go_to, input),
                url,
                R.drawable.ic_language
            ))
        }

        try {
            val historyMatches = withContext(Dispatchers.IO) {
                repo.history.search(sessionId, input, 3)
            }
            historyMatches.forEach { h ->
                suggestions.add(SuggestionItem(
                    SuggestionItem.Type.HISTORY,
                    h.title.ifBlank { h.url },
                    h.url,
                    R.drawable.ic_history
                ))
            }
        } catch (_: Exception) {}

        try {
            val bookmarkMatches = withContext(Dispatchers.IO) {
                repo.bookmarks.search(sessionId, input, 3)
            }
            bookmarkMatches.forEach { b ->
                suggestions.add(SuggestionItem(
                    SuggestionItem.Type.BOOKMARK,
                    b.title.ifBlank { b.url },
                    b.url,
                    R.drawable.ic_star
                ))
            }
        } catch (_: Exception) {}

        return suggestions
    }
}
