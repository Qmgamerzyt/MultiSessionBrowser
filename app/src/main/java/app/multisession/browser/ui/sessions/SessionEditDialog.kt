package app.multisession.browser.ui.sessions

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import app.multisession.browser.R
import app.multisession.browser.data.db.SessionEntity
import app.multisession.browser.session.SessionManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Create / rename a session: name, colour and (for new sessions) the private flag. */
object SessionEditDialog {

    fun show(context: Context, existing: SessionEntity?, onDone: (name: String, color: Int, isPrivate: Boolean) -> Unit) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_session_edit, null)
        val nameInput = view.findViewById<EditText>(R.id.sessionNameInput)
        val colorRow = view.findViewById<LinearLayout>(R.id.colorRow)
        val privateCheck = view.findViewById<CheckBox>(R.id.privateCheck)
        val privateHint = view.findViewById<TextView>(R.id.privateHint)
        privateCheck.isVisible = existing == null
        privateHint.isVisible = existing == null

        nameInput.setText(existing?.name ?: "")
        var selected = existing?.color ?: SessionManager.PALETTE[0]
        val density = context.resources.displayMetrics.density
        val size = (36 * density).toInt()
        val swatches = mutableListOf<Pair<Int, View>>()

        fun render() {
            swatches.forEach { (color, v) ->
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    if (color == selected) setStroke((3 * density).toInt(), if (isDark(color)) Color.WHITE else Color.BLACK)
                }
            }
        }

        SessionManager.PALETTE.forEach { color ->
            val v = View(context)
            val lp = LinearLayout.LayoutParams(size, size).apply { marginEnd = (10 * density).toInt() }
            v.layoutParams = lp
            v.contentDescription = context.getString(R.string.cd_color)
            v.setOnClickListener { selected = color; render() }
            colorRow.addView(v)
            swatches += color to v
        }
        render()

        MaterialAlertDialogBuilder(context)
            .setTitle(if (existing == null) R.string.new_session else R.string.edit_session)
            .setView(view)
            .setPositiveButton(if (existing == null) R.string.create else R.string.save) { _, _ ->
                onDone(nameInput.text.toString(), selected, privateCheck.isChecked)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        nameInput.requestFocus()
    }

    private fun isDark(color: Int): Boolean {
        val r = Color.red(color); val g = Color.green(color); val b = Color.blue(color)
        return (0.299 * r + 0.587 * g + 0.114 * b) < 140
    }
}
