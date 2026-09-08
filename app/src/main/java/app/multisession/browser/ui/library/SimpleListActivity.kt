package app.multisession.browser.ui.library

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.multisession.browser.BrowserApp
import app.multisession.browser.R
import com.google.android.material.appbar.MaterialToolbar

data class LinkItem(
    val title: String,
    val subtitle: String,
    @DrawableRes val icon: Int,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
)

/** Shared scaffold for History / Bookmarks / Projects: toolbar + list + empty state, returns a URL to the browser. */
abstract class SimpleListActivity : AppCompatActivity() {

    protected val core get() = BrowserApp.core()
    protected lateinit var recycler: RecyclerView
    protected lateinit var emptyView: TextView
    protected lateinit var toolbar: MaterialToolbar
    protected val adapter = LinkAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        recycler = findViewById(R.id.recycler)
        emptyView = findViewById(R.id.emptyView)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
    }

    override fun onSupportNavigateUp(): Boolean {
        finish(); return true
    }

    protected fun submit(items: List<LinkItem>, emptyText: String) {
        adapter.submit(items)
        emptyView.text = emptyText
        emptyView.isVisible = items.isEmpty()
    }

    protected fun returnUrl(url: String, newTab: Boolean = false) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_OPEN_URL, url).putExtra(EXTRA_IN_NEW_TAB, newTab))
        finish()
    }

    class LinkAdapter : RecyclerView.Adapter<LinkAdapter.VH>() {
        private val items = mutableListOf<LinkItem>()

        fun submit(list: List<LinkItem>) {
            items.clear(); items.addAll(list); notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_link, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.title.text = item.title
            holder.subtitle.text = item.subtitle
            holder.subtitle.isVisible = item.subtitle.isNotBlank()
            holder.icon.setImageResource(item.icon)
            holder.itemView.setOnClickListener { item.onClick() }
            holder.itemView.setOnLongClickListener { item.onLongClick?.invoke(); item.onLongClick != null }
        }

        class VH(v: View) : RecyclerView.ViewHolder(v) {
            val title: TextView = v.findViewById(R.id.linkTitle)
            val subtitle: TextView = v.findViewById(R.id.linkSubtitle)
            val icon: ImageView = v.findViewById(R.id.linkIcon)
        }
    }

    companion object {
        const val EXTRA_OPEN_URL = "app.multisession.browser.OPEN_URL"
        const val EXTRA_IN_NEW_TAB = "app.multisession.browser.IN_NEW_TAB"
    }
}
