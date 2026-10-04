package app.multisession.browser.projects.editor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast
import app.multisession.browser.R
import app.multisession.browser.core.AppLog
import app.multisession.browser.projects.ProjectManager
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * File core of the in-app HTML project editor (v2.1.10, Plan 3).
 *
 * One instance per editor screen; the annotated methods are handed to the system WebView as
 * `window.Bridge` (un-annotated members are invisible to JS and are called from Kotlin).
 * Every path is project-relative: [resolve] canonicalizes it against the project folder, so
 * `..`, absolute paths and symlink escapes cannot leave it - the same policy as
 * [app.multisession.browser.engine.LocalContentLoader.isAllowedLocalUri].
 */
class EditorBridge(
    context: Context,
    private val project: ProjectManager.Project,
    private val dirtyListener: (Boolean) -> Unit,
) {

    data class TreeEntry(val path: String, val dir: Boolean, val size: Long)

    private val appContext = context.applicationContext
    private val root: File = project.dir
    private val main = Handler(Looper.getMainLooper())
    private val maxBytes = 2L * 1024 * 1024

    /** Editable text extensions (spec "File policy"). */
    private val allow = setOf("html", "htm", "css", "js", "mjs", "json", "txt", "md", "svg", "xml")

    // ProjectManager.META is private; the editor must never write/delete our own metadata file.
    private val readOnly = "project.json"

    // ------------------------------------------------------------------ filesystem core (any thread)

    private fun resolve(rel: String): File? {
        if (rel.isBlank()) return null
        return try {
            val f = File(root, rel).canonicalFile
            val rootPath = root.canonicalPath
            if (f.path == rootPath || f.path.startsWith(rootPath + File.separator)) f else null
        } catch (t: Throwable) {
            null
        }
    }

    private fun editable(rel: String): Boolean {
        val name = rel.substringAfterLast('/')
        if (name.isEmpty() || name == readOnly) return false
        return name.substringAfterLast('.', "").lowercase() in allow
    }

    /** Every file and directory under the project root, `/`-separated relative paths. */
    fun list(): List<TreeEntry> {
        val out = mutableListOf<TreeEntry>()
        root.walkTopDown().forEach { f ->
            if (f == root) return@forEach
            val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
            if (rel == "__MACOSX" || rel.startsWith("__MACOSX/")) return@forEach
            out += TreeEntry(rel, f.isDirectory, f.length())
        }
        return out
    }

    /** Strict-UTF-8 read. Binary, oversized and non-allowlisted files are refused. */
    fun open(rel: String): JSONObject {
        val f = resolve(rel)
        if (f == null || !f.isFile) return err(appContext.getString(R.string.editor_bad_path))
        if (f.name == readOnly) {
            // Our own metadata: viewable but never saved through the editor.
            return try {
                JSONObject().put("ok", true).put("content", f.readText())
            } catch (t: Throwable) {
                err(appContext.getString(R.string.editor_binary))
            }
        }
        if (!editable(f.name)) return err(appContext.getString(R.string.editor_binary))
        if (f.length() > maxBytes) return err(appContext.getString(R.string.editor_too_large))
        return try {
            val text = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(f.readBytes())).toString()
            JSONObject().put("ok", true).put("content", text)
        } catch (e: CharacterCodingException) {
            err(appContext.getString(R.string.editor_binary))
        }
    }

    /** New empty file; the parent directory must already exist (spec). */
    fun create(rel: String): Boolean {
        val f = resolve(rel) ?: return false
        if (f.exists() || !editable(f.name)) return false
        val parent = f.parentFile ?: return false
        if (!parent.isDirectory) return false
        return try {
            f.writeText(""); true
        } catch (t: Throwable) {
            AppLog.e(TAG, "create failed", t); false
        }
    }

    fun rename(from: String, to: String): Boolean {
        val a = resolve(from) ?: return false
        val b = resolve(to) ?: return false
        if (!a.isFile || !editable(a.name) || !editable(b.name) || b.exists()) return false
        val parent = b.parentFile ?: return false
        if (!parent.isDirectory) return false
        return a.renameTo(b)
    }

    fun delete(rel: String): Boolean {
        val f = resolve(rel) ?: return false
        // Key off the CANONICAL name: raw-string checks are bypassable via "project.json/." etc.
        if (f.name == readOnly) return false
        return f.isFile && f.delete()
    }

    private fun err(msg: String) = JSONObject().put("ok", false).put("error", msg)

    // ------------------------------------------------------------------ JS surface (WebView worker thread)

    /** JS `Bridge.openPath(path)` -> `{ok, content|error}`; failure is also toasted. */
    @JavascriptInterface
    fun openPath(rel: String): String {
        val res = open(rel)
        if (!res.optBoolean("ok")) postToast(res.getString("error"))
        return res.toString()
    }

    /**
     * JS `Bridge.save(path, content)` -> `"ok"` / `"err"`. Writes `name.tmp` beside the target
     * and renames over it, so a crash mid-write never truncates the original.
     */
    @JavascriptInterface
    fun save(rel: String, content: String): String {
        val f = resolve(rel)
        if (f == null || !f.isFile || !editable(f.name)) {
            postToast(appContext.getString(R.string.editor_save_failed))
            return "err"
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) {
            postToast(appContext.getString(R.string.editor_too_large))
            return "err"
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        return try {
            tmp.writeBytes(bytes)
            if (tmp.renameTo(f)) "ok" else {
                tmp.delete()
                postToast(appContext.getString(R.string.editor_save_failed)); "err"
            }
        } catch (t: Throwable) {
            tmp.delete()
            AppLog.e(TAG, "save failed", t)
            postToast(appContext.getString(R.string.editor_save_failed))
            "err"
        }
    }

    /** JS dirty-flag push; [dirtyListener] is posted to the main thread. */
    @JavascriptInterface
    fun onDirty(dirty: Boolean) {
        main.post { dirtyListener.invoke(dirty) }
    }

    private fun postToast(msg: String) {
        main.post { Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show() }
    }

    private companion object {
        const val TAG = "Editor"
    }
}
