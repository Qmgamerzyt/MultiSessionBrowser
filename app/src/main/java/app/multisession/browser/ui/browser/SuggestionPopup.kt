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

/**
 * Chrome-like address-bar suggestions. Visibility is *derived* from [isSearchActive]
 * (address bar focused): the popup is only ever shown while that predicate is true and is
 * re-checked after the asynchronous lookup, so a stale result can never re-open it.
 */
class SuggestionPopup(
    private val context: Context,
    private val repo: BrowserRepository,
    private val scope: CoroutineScope,
    private val isSearchActive: () -> Boolean,
    private val onItemClick: (SuggestionItem) -> Unit
) {
    private val adapter = SuggestionAdapter(context)
    private val popup = ListPopupWindow(context)
    private var searchJob: Job? = null

    init {
        popup.anchorView = null // set later via anchor()
        popup.setAdapter(adapter)
        popup.isModal = false // keeps the keyboard/focus on the EditText while typing
        popup.inputMethodMode = ListPopupWindow.INPUT_METHOD_NEEDED // never overlap the keyboard
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
        searchJob = null
        if (input.isBlank() || !isSearchActive()) {
            dismiss()
            return
        }
        searchJob = scope.launch {
            val suggestions = generateSuggestions(input, sessionId)
            withContext(Dispatchers.Main) {
                // State may have changed while the lookup ran (navigation, focus loss, tab switch).
                if (suggestions.isEmpty() || !isSearchActive() || popup.anchorView?.isAttachedToWindow != true) {
                    dismiss()
                    return@withContext
                }
                adapter.update(suggestions)
                if (!popup.isShowing) {
                    try {
                        popup.show()
                    } catch (t: Throwable) {
                        // e.g. window token gone during teardown: silently stay hidden
                        adapter.clear()
                    }
                }
            }
        }
    }

    fun dismiss() {
        searchJob?.cancel()
        searchJob = null
        if (popup.isShowing) popup.dismiss()
        adapter.clear()
    }

    val isShowing: Boolean get() = popup.isShowing

    private suspend fun generateSuggestions(input: String, sessionId: String): List<SuggestionItem> {
        val suggestions = mutableListOf<SuggestionItem>()

        // v2.2.0-beta-3: the ACTION row comes FIRST - anything that is a URL or an executable ':'
        // command offers "open it" above "search for it" (backspacing https:// off a typed URL used
        // to leave only the search row / bury the navigate row under it).
        if (UrlUtils.looksLikeHost(input)) {
            val url = if (input.startsWith("http")) input else "https://$input"
            suggestions.add(SuggestionItem(
                SuggestionItem.Type.NAVIGATE,
                context.getString(R.string.suggestion_go_to, input),
                url,
                R.drawable.ic_language
            ))
        } else if (input.contains(':')) {
            suggestions.add(SuggestionItem(
                SuggestionItem.Type.NAVIGATE,
                context.getString(R.string.suggestion_execute, input),
                UrlUtils.resolveInput(input),
                R.drawable.ic_play
            ))
        }

        suggestions.add(SuggestionItem(
            SuggestionItem.Type.SEARCH,
            context.getString(R.string.suggestion_search, input),
            Prefs.searchUrlFor(input),
            R.drawable.ic_search
        ))

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
