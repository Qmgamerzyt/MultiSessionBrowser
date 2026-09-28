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
 *
 * Every prompt is answered through [Resolution], which is what keeps the delegate crash-free:
 * GeckoView's `BasePrompt.confirm()`/`dismiss()` throw `"Cannot confirm/dismiss a Prompt twice."`,
 * so the response must be *produced* only after this object has claimed the prompt. Callers hand
 * over a lambda (`finish { prompt.dismiss() }`) instead of a value, because a value would be
 * evaluated eagerly — the exact shape of the old `finish(prompt.dismiss())` double-dismiss crash.
 */
class BrowserPromptDelegate(private val core: BrowserCore, private val tab: Tab) : GeckoSession.PromptDelegate {

    private val host: BrowserHost? get() = core.tabs.host

    /**
     * Single-answer guard for one prompt: `UNRESOLVED -> ANSWERED`, never twice.
     *
     * The [respond] lambda is invoked only after the guard is claimed, so `prompt.confirm(...)`
     * / `prompt.dismiss()` runs at most once, and the `GeckoResult` is completed exactly once.
     * Everything happens on the UI thread (all of these entry points are `@UiThread`), so the
     * check-then-act below cannot interleave.
     */
    private class Resolution(private val prompt: BasePrompt, private val result: GeckoResult<PromptResponse>) {

        private var answered = false

        /**
         * Completes the prompt with the value produced by [respond] and resolves the pending
         * `GeckoResult`. @return true when this call was the one that answered the prompt.
         */
        fun answer(respond: () -> PromptResponse): Boolean {
            if (answered) {
                AppLog.w(TAG, "Dropping duplicate answer for ${prompt::class.java.simpleName}")
                return false
            }
            if (prompt.isComplete) {
                // Only this class completes prompts, so guard and GeckoView disagree. Claim it
                // and never touch the prompt again — calling into it would throw.
                AppLog.w(TAG, "Prompt already complete for ${prompt::class.java.simpleName}")
                answered = true
                return false
            }
            // Claim before producing: respond() and result.complete() may re-enter dismiss paths.
            answered = true
            result.complete(respond())
            return true
        }
    }

    override fun onAlertPrompt(session: GeckoSession, prompt: AlertPrompt): GeckoResult<PromptResponse>? =
        show(prompt) { ctx, finish ->
            MaterialAlertDialogBuilder(ctx)
                .setTitle(prompt.title ?: pageHost())
                .setMessage(prompt.message)
                .setPositiveButton(android.R.string.ok) { _, _ -> finish { prompt.dismiss() } }
                .create()
        }

    override fun onButtonPrompt(session: GeckoSession, prompt: ButtonPrompt): GeckoResult<PromptResponse>? =
        show(prompt) { ctx, finish ->
            MaterialAlertDialogBuilder(ctx)
                .setTitle(prompt.title ?: pageHost())
                .setMessage(prompt.message)
                .setPositiveButton(android.R.string.ok) { _, _ -> finish { prompt.confirm(ButtonPrompt.Type.POSITIVE) } }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish { prompt.confirm(ButtonPrompt.Type.NEGATIVE) } }
                .create()
        }

    override fun onTextPrompt(session: GeckoSession, prompt: TextPrompt): GeckoResult<PromptResponse>? =
        show(prompt) { ctx, finish ->
            val input = EditText(ctx).apply { setText(prompt.defaultValue ?: ""); setSelectAllOnFocus(true) }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(prompt.title ?: pageHost())
                .setMessage(prompt.message)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ -> finish { prompt.confirm(input.text.toString()) } }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish { prompt.dismiss() } }
                .create()
        }

    override fun onBeforeUnloadPrompt(session: GeckoSession, prompt: BeforeUnloadPrompt): GeckoResult<PromptResponse>? =
        show(prompt) { ctx, finish ->
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.leave_page_title)
                .setMessage(R.string.leave_page_message)
                .setPositiveButton(R.string.leave) { _, _ -> finish { prompt.confirm(AllowOrDeny.ALLOW) } }
                .setNegativeButton(R.string.stay) { _, _ -> finish { prompt.confirm(AllowOrDeny.DENY) } }
                .create()
        }

    override fun onRepostConfirmPrompt(session: GeckoSession, prompt: RepostConfirmPrompt): GeckoResult<PromptResponse>? =
        show(prompt) { ctx, finish ->
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.repost_title)
                .setMessage(R.string.repost_message)
                .setPositiveButton(R.string.resend) { _, _ -> finish { prompt.confirm(AllowOrDeny.ALLOW) } }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish { prompt.confirm(AllowOrDeny.DENY) } }
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
                    finish {
                        if (onlyPassword) prompt.confirm(pass.text.toString())
                        else prompt.confirm(user.text.toString(), pass.text.toString())
                    }
                }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish { prompt.dismiss() } }
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
                        val picked = flat.filterIndexed { i, _ -> checked[i] }.map { it.id }.toTypedArray()
                        finish { prompt.confirm(picked) }
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> finish { prompt.dismiss() } }
            } else {
                val current = flat.indexOfFirst { it.selected }
                builder.setSingleChoiceItems(labels, current) { d, i ->
                    if (flat[i].disabled) return@setSingleChoiceItems
                    val picked = flat[i]
                    finish { prompt.confirm(picked) }
                    // Closing the dialog re-enters the dismiss listener; the guard makes it a no-op.
                    d.dismiss()
                }.setNegativeButton(android.R.string.cancel) { _, _ -> finish { prompt.dismiss() } }
            }
            builder.create()
        }

    override fun onColorPrompt(session: GeckoSession, prompt: ColorPrompt): GeckoResult<PromptResponse>? =
        show(prompt) { ctx, finish ->
            val input = EditText(ctx).apply { setText(prompt.defaultValue ?: "#000000"); hint = "#RRGGBB" }
            MaterialAlertDialogBuilder(ctx)
                .setTitle(R.string.pick_color)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ -> finish { prompt.confirm(input.text.toString()) } }
                .setNegativeButton(android.R.string.cancel) { _, _ -> finish { prompt.dismiss() } }
                .create()
        }

    /** <input type=date|time|month|week|datetime-local>. Values are exchanged in the HTML value format. */
    override fun onDateTimePrompt(session: GeckoSession, prompt: DateTimePrompt): GeckoResult<PromptResponse>? {
        val act = host?.activity ?: return answerNow(prompt) { prompt.dismiss() }
        if (act.isFinishing || act.isDestroyed) return answerNow(prompt) { prompt.dismiss() }
        val result = GeckoResult<PromptResponse>()
        val resolution = Resolution(prompt, result)
        val finish: (() -> PromptResponse) -> Unit = { respond -> resolution.answer(respond) }
        // Gecko withdrew the prompt (navigated away): answer first, then close the picker.
        fun attachWithdrawGuard(dialog: android.app.Dialog) {
            prompt.setDelegate(object : PromptInstanceDelegate {
                override fun onPromptDismiss(p: BasePrompt) {
                    resolution.answer { prompt.dismiss() }
                    if (dialog.isShowing) dialog.dismiss()
                }
            })
        }
        val cal = Calendar.getInstance()
        when (prompt.type) {
            DateTimePrompt.Type.DATE -> {
                Regex("(\\d{4})-(\\d{2})-(\\d{2})").find(prompt.defaultValue ?: "")?.let { m ->
                    cal.set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt())
                }
                val dlg = DatePickerDialog(act, { _, y, mo, d -> finish { prompt.confirm(String.format("%04d-%02d-%02d", y, mo + 1, d)) } },
                    cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
                dlg.setOnDismissListener { resolution.answer { prompt.dismiss() } }
                attachWithdrawGuard(dlg)
                dlg.show()
            }
            DateTimePrompt.Type.TIME -> {
                Regex("(\\d{2}):(\\d{2})").find(prompt.defaultValue ?: "")?.let { m ->
                    cal.set(Calendar.HOUR_OF_DAY, m.groupValues[1].toInt()); cal.set(Calendar.MINUTE, m.groupValues[2].toInt())
                }
                val dlg = TimePickerDialog(act, { _, h, m -> finish { prompt.confirm(String.format("%02d:%02d", h, m)) } },
                    cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), android.text.format.DateFormat.is24HourFormat(act))
                dlg.setOnDismissListener { resolution.answer { prompt.dismiss() } }
                attachWithdrawGuard(dlg)
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
                    .setPositiveButton(android.R.string.ok) { _, _ -> finish { prompt.confirm(input.text.toString()) } }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> finish { prompt.dismiss() } }
                    .create()
                dlg.setOnDismissListener { resolution.answer { prompt.dismiss() } }
                attachWithdrawGuard(dlg)
                dlg.show()
            }
        }
        return result
    }

    /** <input type=file>: the host runs the system picker (documents, gallery, camera capture). */
    override fun onFilePrompt(session: GeckoSession, prompt: FilePrompt): GeckoResult<PromptResponse>? {
        val h = host ?: return answerNow(prompt) { prompt.dismiss() }
        val result = GeckoResult<PromptResponse>()
        val resolution = Resolution(prompt, result)
        // Gecko can withdraw this prompt while the system picker is still open (the page navigates
        // away, the session is closed). Without this delegate the `result` would never be completed
        // and, worse, the picker's callback would later confirm/dismiss a prompt Gecko already
        // dropped - exactly the double-answer this class exists to prevent.
        prompt.setDelegate(object : PromptInstanceDelegate {
            override fun onPromptDismiss(p: BasePrompt) {
                resolution.answer { prompt.dismiss() }
            }
        })
        val multiple = prompt.type == FilePrompt.Type.MULTIPLE
        val capture = prompt.capture != FilePrompt.Capture.NONE
        h.pickFiles(prompt.mimeTypes, multiple, capture) { uris ->
            resolution.answer {
                when {
                    uris.isNullOrEmpty() -> prompt.dismiss()
                    multiple -> prompt.confirm(h.activity, uris)
                    else -> prompt.confirm(h.activity, uris[0])
                }
            }
        }
        return result
    }

    /** Gecko's popup blocker caught a window.open without user gesture: keep it blocked. */
    override fun onPopupPrompt(session: GeckoSession, prompt: PopupPrompt): GeckoResult<PromptResponse>? {
        AppLog.i(TAG, "Popup blocked (no user gesture): ${prompt.targetUri?.take(60)}")
        return answerNow(prompt) { prompt.confirm(AllowOrDeny.DENY) }
    }

    /** navigator.share(): hand the payload to the Android share sheet. */
    override fun onSharePrompt(session: GeckoSession, prompt: SharePrompt): GeckoResult<PromptResponse>? {
        val act = host?.activity ?: return answerNow(prompt) { prompt.confirm(SharePrompt.Result.FAILURE) }
        val text = listOfNotNull(prompt.text, prompt.uri).joinToString("\n")
        return try {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            prompt.title?.let { send.putExtra(Intent.EXTRA_SUBJECT, it) }
            act.startActivity(Intent.createChooser(send, act.getString(R.string.share)))
            answerNow(prompt) { prompt.confirm(SharePrompt.Result.SUCCESS) }
        } catch (t: Throwable) {
            answerNow(prompt) { prompt.confirm(SharePrompt.Result.FAILURE) }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun pageHost(): String = UrlUtils.displayHost(tab.url).ifBlank { tab.url }

    /**
     * Resolves a prompt that has no UI to attach to (no host yet, or the Activity is going away)
     * with a plain dismiss. Geckoview treats this exactly like returning `null` — `PromptController`
     * turns it into `callback.sendSuccess(null)` — but unlike `null` it also completes the prompt,
     * so GeckoView drops it from `PromptStorage` instead of keeping a dangling entry.
     */
    private fun answerNow(prompt: BasePrompt, respond: () -> PromptResponse): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        Resolution(prompt, result).answer(respond)
        return result
    }

    /**
     * Shows a dialog for [prompt] and returns the pending result. The result is completed exactly
     * once: by a button, by the dialog being dismissed (-> prompt.dismiss()), or when Gecko itself
     * withdrew the prompt (page navigated away), in which case the dialog is closed after the
     * prompt has already been answered.
     */
    private fun show(
        prompt: BasePrompt,
        build: (Context, (() -> PromptResponse) -> Unit) -> AlertDialog,
    ): GeckoResult<PromptResponse> {
        val act = host?.activity ?: return answerNow(prompt) { prompt.dismiss() }
        if (act.isFinishing || act.isDestroyed) return answerNow(prompt) { prompt.dismiss() }
        val result = GeckoResult<PromptResponse>()
        val resolution = Resolution(prompt, result)
        val finish: (() -> PromptResponse) -> Unit = { respond -> resolution.answer(respond) }
        val dialog = build(act, finish)
        // Android dismisses the dialog itself after a button click, so this fires on every answer:
        // the guard makes the second call a no-op instead of a GeckoView throw.
        dialog.setOnDismissListener { resolution.answer { prompt.dismiss() } }
        prompt.setDelegate(object : PromptInstanceDelegate {
            override fun onPromptDismiss(p: BasePrompt) {
                // GeckoView only withdrew the UI here; the prompt is still open on our side.
                resolution.answer { prompt.dismiss() }
                if (dialog.isShowing) dialog.dismiss()
            }
        })
        dialog.show()
        return result
    }

    private companion object {
        const val TAG = "Prompts"
    }
}
