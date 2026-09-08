package app.multisession.browser.ui.browser

import androidx.annotation.DrawableRes

data class SuggestionItem(
    val type: Type,
    val title: String,
    val url: String,
    @DrawableRes val iconRes: Int
) {
    enum class Type { SEARCH, NAVIGATE, HISTORY, BOOKMARK }
}
