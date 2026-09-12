package app.multisession.browser.engine

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import app.multisession.browser.R
import app.multisession.browser.core.AppLog
import app.multisession.browser.core.BrowserCore
import app.multisession.browser.core.UrlUtils
import app.multisession.browser.tabs.Tab
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AlertPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.AuthPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BasePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.BeforeUnloadPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ButtonPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ChoicePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.ColorPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.DateTimePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.FilePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PopupPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptInstanceDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoSession.PromptDelegate.RepostConfirmPrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.SharePrompt
import org.mozilla.geckoview.GeckoSession.PromptDelegate.TextPrompt
import java.util.Calendar

/**
 * Native UI for everything Gecko asks the embedder to show: JS alert/confirm/prompt,
 * <select> menus, date/time inputs, HTTP authentication, file upload, beforeunload, popups.
 * Dialogs are dismissed automatically when the page navigates away (PromptInstanceDelegate).
 */
class BrowserPromptDelegate(private val core: BrowserCore, private val tab: Tab) : GeckoSession.PromptDelegate {

    private val host: BrowserHost? get() = core.tabs.host

    override fun onAlertPrompt(session: GeckoSession, prompt: AlertPrompt): GeckoResult<PromptResponse>? =
        show(prompt) { ctx, finish ->
            MaterialAlertDialogBuilder(ctx)
                .setTitle(prompt.title ?: pageHost())
                .setMessage(prompt.message)
                .setPositiveButton(android.R.string.ok) { _, _ -> finish(prompt.dismiss()) }
                .create()
        }

    override fun onButtonPrompt(session: GeckoSession, prompt: ButtonPrompt): GeckoResult<PromptResponse>? =
        show(prompt) { ctx, finish ->
            MaterialAlertDialogBuilder(ctx)
                .setTitle(prompt.title ?: pageHost())
                .setMessage(prompt.message)
                .setPositiveButton(android.R.string.ok) { _, _ -> finish(prompt.confirm(ButtonPrompt.Type.POSITIVE)) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish(prompt.confirm(ButtonPrompt.Type.NEGATIVE)) }
                .create()
        }

    override fun onTextPrompt(session: GeckoSession, prompt: TextPrompt): GeckoResult<PromptResponse>? =
        show(prompt) { ctx, finish ->
            val input = EditText(ctx).apply { setText(prompt.defaultValue ?: ""); setSelectAllOnFocus(true) }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(prompt.title ?: pageHost())
                .setMessage(prompt.message)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ -> finish(prompt.confirm(input.text.toString())) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish(prompt.dismiss()) }
                .create()
        }

    override fun onBeforeUnloadPrompt(session: GeckoSession, prompt: BeforeUnloadPrompt): GeckoResult<PromptResponse>? =
        show(prompt) { ctx, finish ->
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.leave_page_title)
                .setMessage(R.string.leave_page_message)
                .setPositiveButton(R.string.leave) { _, _ -> finish(prompt.confirm(AllowOrDeny.ALLOW)) }
                .setNegativeButton(R.string.stay) { _, _ -> finish(prompt.confirm(AllowOrDeny.DENY)) }
                .create()
        }

    override fun onRepostConfirmPrompt(session: GeckoSession, prompt: RepostConfirmPrompt): GeckoResult<PromptResponse>? =
        show(prompt) { ctx, finish ->
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.repost_title)
                .setMessage(R.string.repost_message)
                .setPositiveButton(R.string.resend) { _, _ -> finish(prompt.confirm(AllowOrDeny.ALLOW)) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish(prompt.confirm(AllowOrDeny.DENY)) }
                .create()
        }

    /** HTTP Basic/Digest and proxy authentication. Credentials go straight to Gecko; nothing is stored. */
    override fun onAuthPrompt(session: GeckoSession, prompt: AuthPrompt): GeckoResult<PromptResponse>? =
        show(prompt) { ctx, finish ->
            val view = LayoutInflater.from(ctx).inflate(R.layout.dialog_http_auth, null)
            val user = view.findViewById<EditText>(R.id.authUser)
            val pass = view.findViewById<EditText>(R.id.authPassword)
            val onlyPassword = (prompt.authOptions.flags and AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD) != 0
            user.setText(prompt.authOptions.username ?: "")
            if (onlyPassword) user.visibility = android.view.View.GONE
            val hostName = UrlUtils.displayHost(prompt.authOptions.uri).ifBlank { pageHost() }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(ctx.getString(R.string.auth_title, hostName))
                .setMessage(prompt.message ?: prompt.title)
                .setView(view)
                .setPositiveButton(R.string.sign_in) { _, _ ->
                    finish(if (onlyPassword) prompt.confirm(pass.text.toString()) else prompt.confirm(user.text.toString(), pass.text.toString()))
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish(prompt.dismiss()) }
                .create()
        }

    /** <select> elements and context menus (single / multiple choice, with optgroup flattening). */
    override fun onChoicePrompt(session: GeckoSession, prompt: ChoicePrompt): GeckoResult<PromptResponse>? =
        show(prompt) { ctx, finish ->
            val flat = mutableListOf<ChoicePrompt.Choice>()
            fun add(list: Array<ChoicePrompt.Choice>) {
                list.forEach { c -> if (c.items != null) add(c.items!!) else if (!c.separator) flat += c }
            }
            add(prompt.choices)
            val labels = flat.map { if (it.disabled) "  ${it.label}" else it.label }.toTypedArray()
            val builder = MaterialAlertDialogBuilder(ctx).setTitle(prompt.title ?: prompt.message)
            if (prompt.type == ChoicePrompt.Type.MULTIPLE) {
                val checked = BooleanArray(flat.size) { flat[it].selected }
                builder.setMultiChoiceItems(labels, checked) { _, i, v -> checked[i] = v || flat[i].disabled && flat[i].selected }
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        finish(prompt.confirm(flat.filterIndexed { i, _ -> checked[i] }.map { it.id }.toTypedArray()))
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> finish(prompt.dismiss()) }
            } else {
                val current = flat.indexOfFirst { it.selected }
                builder.setSingleChoiceItems(labels, current) { d, i ->
                    if (flat[i].disabled) return@setSingleChoiceItems
                    finish(prompt.confirm(flat[i]))
                    d.dismiss()
                }.setNegativeButton(android.R.string.cancel) { _, _ -> finish(prompt.dismiss()) }
            }
            builder.create()
        }

    override fun onColorPrompt(session: GeckoSession, prompt: ColorPrompt): GeckoResult<PromptResponse>? =
        show(prompt) { ctx, finish ->
            val input = EditText(ctx).apply { setText(prompt.defaultValue ?: "#000000"); hint = "#RRGGBB" }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.pick_color)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ -> finish(prompt.confirm(input.text.toString())) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish(prompt.dismiss()) }
                .create()
        }

    /** <input type=date|time|month|week|datetime-local>. Values are exchanged in the HTML value format. */
    override fun onDateTimePrompt(session: GeckoSession, prompt: DateTimePrompt): GeckoResult<PromptResponse>? {
        val act = host?.activity ?: return null
        val result = GeckoResult<PromptResponse>()
        var finished = false
        val finish: (PromptResponse) -> Unit = { r -> if (!finished) { finished = true; result.complete(r) } }
        val cal = Calendar.getInstance()
        when (prompt.type) {
            DateTimePrompt.Type.DATE -> {
                Regex("(\\d{4})-(\\d{2})-(\\d{2})").find(prompt.defaultValue ?: "")?.let { m ->
                    cal.set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt())
                }
                val dlg = DatePickerDialog(act, { _, y, mo, d -> finish(prompt.confirm(String.format("%04d-%02d-%02d", y, mo + 1, d))) },
                    cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
                dlg.setOnDismissListener { finish(prompt.dismiss()) }
                prompt.setDelegate(object : PromptInstanceDelegate { override fun onPromptDismiss(p: BasePrompt) { if (dlg.isShowing) dlg.dismiss() } })
                dlg.show()
            }
            DateTimePrompt.Type.TIME -> {
                Regex("(\\d{2}):(\\d{2})").find(prompt.defaultValue ?: "")?.let { m ->
                    cal.set(Calendar.HOUR_OF_DAY, m.groupValues[1].toInt()); cal.set(Calendar.MINUTE, m.groupValues[2].toInt())
                }
                val dlg = TimePickerDialog(act, { _, h, m -> finish(prompt.confirm(String.format("%02d:%02d", h, m))) },
                    cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(act))
                dlg.setOnDismissListener { finish(prompt.dismiss()) }
                prompt.setDelegate(object : PromptInstanceDelegate { override fun onPromptDismiss(p: BasePrompt) { if (dlg.isShowing) dlg.dismiss() } })
                dlg.show()
            }
            else -> {
                val hint = when (prompt.type) {
                    DateTimePrompt.Type.MONTH -> "YYYY-MM"
                    DateTimePrompt.Type.WEEK -> "YYYY-Www"
                    else -> "YYYY-MM-DDTHH:MM"
                }
                val input = EditText(act).apply { setText(prompt.defaultValue ?: ""); this.hint = hint }
                val dlg = MaterialAlertDialogBuilder(act)
                    .setTitle(prompt.title ?: hint)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ -> finish(prompt.confirm(input.text.toString())) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> finish(prompt.dismiss()) }
                    .create()
                dlg.setOnDismissListener { finish(prompt.dismiss()) }
                prompt.setDelegate(object : PromptInstanceDelegate { override fun onPromptDismiss(p: BasePrompt) { if (dlg.isShowing) dlg.dismiss() } })
                dlg.show()
            }
        }
        return result
    }

    /** <input type=file>: the host runs the system picker (documents, gallery, camera capture). */
    override fun onFilePrompt(session: GeckoSession, prompt: FilePrompt): GeckoResult<PromptResponse>? {
        val h = host ?: return null
        val result = GeckoResult<PromptResponse>()
        val multiple = prompt.type == FilePrompt.Type.MULTIPLE
        val capture = prompt.capture != FilePrompt.Capture.NONE
        h.pickFiles(prompt.mimeTypes, multiple, capture) { uris ->
            if (prompt.isComplete) return@pickFiles
            when {
                uris.isNullOrEmpty() -> result.complete(prompt.dismiss())
                multiple -> result.complete(prompt.confirm(h.activity, uris))
                else -> result.complete(prompt.confirm(h.activity, uris[0]))
            }
        }
        return result
    }

    /** Gecko's popup blocker caught a window.open without user gesture: keep it blocked. */
    override fun onPopupPrompt(session: GeckoSession, prompt: PopupPrompt): GeckoResult<PromptResponse>? {
        AppLog.i(TAG, "Popup blocked (no user gesture): ${prompt.targetUri?.take(60)}")
        return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
    }

    /** navigator.share(): hand the payload to the Android share sheet. */
    override fun onSharePrompt(session: GeckoSession, prompt: SharePrompt): GeckoResult<PromptResponse>? {
        val act = host?.activity ?: return GeckoResult.fromValue(prompt.confirm(SharePrompt.Result.FAILURE))
        val text = listOfNotNull(prompt.text, prompt.uri).joinToString("\n")
        return try {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            prompt.title?.let { send.putExtra(Intent.EXTRA_SUBJECT, it) }
            act.startActivity(Intent.createChooser(send, act.getString(R.string.share)))
            GeckoResult.fromValue(prompt.confirm(SharePrompt.Result.SUCCESS))
        } catch (t: Throwable) {
            GeckoResult.fromValue(prompt.confirm(SharePrompt.Result.FAILURE))
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun pageHost(): String = UrlUtils.displayHost(tab.url).ifBlank { tab.url }

    /**
     * Shows a dialog for [prompt] and returns the pending result. The result is completed exactly
     * once: by a button, by the dialog being dismissed (-> prompt.dismiss()), or ignored when Gecko
     * itself withdrew the prompt (page navigated away), in which case the dialog is closed.
     */
    private fun show(prompt: BasePrompt, build: (Context, (PromptResponse) -> Unit) -> AlertDialog): GeckoResult<PromptResponse>? {
        val act = host?.activity ?: return null
        if (act.isFinishing || act.isDestroyed) return null
        val result = GeckoResult<PromptResponse>()
        var finished = false
        val finish: (PromptResponse) -> Unit = { r -> if (!finished) { finished = true; result.complete(r) } }
        val dialog = build(act, finish)
        dialog.setOnDismissListener { finish(prompt.dismiss()) }
        prompt.setDelegate(object : PromptInstanceDelegate {
            override fun onPromptDismiss(p: BasePrompt) { if (dialog.isShowing) dialog.dismiss() }
        })
        dialog.show()
        return result
    }

    private companion object {
        const val TAG = "Prompts"
    }
}
