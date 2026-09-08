package app.multisession.browser.ui.browser

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import app.multisession.browser.R

class SuggestionAdapter(private val context: Context) : BaseAdapter() {

    private var items: List<SuggestionItem> = emptyList()

    fun update(newItems: List<SuggestionItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun clear() {
        items = emptyList()
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size
    override fun getItem(position: Int): SuggestionItem = items[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_suggestion, parent, false)
        val item = items[position]

        view.findViewById<ImageView>(R.id.suggestionIcon).setImageResource(item.iconRes)
        view.findViewById<TextView>(R.id.suggestionTitle).text = item.title

        val urlView = view.findViewById<TextView>(R.id.suggestionUrl)
        if (item.type == SuggestionItem.Type.HISTORY || item.type == SuggestionItem.Type.BOOKMARK) {
            urlView.text = item.url
            urlView.visibility = View.VISIBLE
        } else {
            urlView.visibility = View.GONE
        }

        return view
    }
}
