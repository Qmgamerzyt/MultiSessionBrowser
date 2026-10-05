package app.multisession.browser.extensions

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import app.multisession.browser.core.AppLog
import app.multisession.browser.core.BrowserCore
import app.multisession.browser.downloads.DownloadTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

/** Log tag + tuning for this file; `private` so it is visible to [Target] as well. */
private const val TAG = "ExtDownload"
private const val BUFFER_SIZE = 32 * 1024
private const val PROGRESS_STEP = 64L * 1024L

/**
 * The state Gecko reads for one add-on initiated download. [WebExtension.Download.Info] is an
 * interface of `@UiThread` getters, so this is a plain immutable snapshot: Gecko asks for the
 * current values whenever it builds a `downloads.onChanged` payload, and nothing here touches a
 * view or blocks, which makes it safe to construct on any thread.
 *
 * -1 is Gecko's own "unknown" value for both [totalBytes] and [fileSize].
 */
internal class ExtensionDownloadInfo(
    private val downloadState: Int,
    private val received: Long,
    private val total: Long,
    private val fileName: String,
    private val mimeType: String,
    private val startedAt: Long,
    private val endedAt: Long?,
    private val errorReason: Int?,
    private val exists: Boolean,
    private val referrerUrl: String,
) : WebExtension.Download.Info {
    override fun state(): Int = downloadState
    override fun bytesReceived(): Long = received
    override fun totalBytes(): Long = total
    override fun filename(): String = fileName
    override fun mime(): String = mimeType
    override fun startTime(): Long = startedAt
    override fun endTime(): Long? = endedAt
    override fun estimatedEndTime(): Long? = null
    override fun error(): Int? = errorReason
    override fun fileExists(): Boolean = exists
    override fun fileSize(): Long = if (exists && received > 0) received else -1L
    // No pause/resume plumbing: the bytes are written straight through, so neither can ever be true.
    override fun paused(): Boolean = false
    override fun canResume(): Boolean = false
    override fun referrer(): String = referrerUrl
}

/** Destination of an add-on download: a MediaStore row on Android 10+, a file in the public
 *  Downloads folder on Android 9. The file is deliberately NOT added to the app's own downloads
 *  list (v2.1.8 choice): it is saved and reported to the add-on, the Downloads screen stays
 *  page-initiated only, and therefore no Room schema or migration is involved. */
private class Target(
    val stream: OutputStream,
    val name: String,
    private val contentUri: Uri?,
    private val filePath: String?,
) {
    /** Marks the file complete and returns the name the storage layer actually used. */
    @Suppress("DEPRECATION")   // addCompletedDownload / getExternalStoragePublicDirectory (Android 9 branch only)
    fun commit(context: Context, mime: String): String {
        contentUri?.let { uri ->
            try {
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (t: Throwable) {
                AppLog.w(TAG, "commit failed", t)
            }
            return context.contentResolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: name
        }
        filePath?.let { p ->
            val f = File(p)
            try {
                // Same call AppDownloadManager makes: without it the file is only visible to this app.
                context.getSystemService(DownloadManager::class.java)
                    .addCompletedDownload(f.name, f.name, true, mime, f.absolutePath, f.length(), false)
            } catch (_: Throwable) {}
            return f.name
        }
        return name
    }

    /** Removes a half-written destination so a failed download never leaves junk behind. */
    fun discard(context: Context) {
        try {
            contentUri?.let { context.contentResolver.delete(it, null, null) }
            filePath?.let { File(it).delete() }
        } catch (t: Throwable) {
            AppLog.w(TAG, "discard failed", t)
        }
    }
}

/**
 * Implements `downloads.download()` for add-ons (v2.1.8, Q6a).
 *
 * Before this existed `WebExtensionController.download()` found no delegate, parked the message in
 * `mPendingDownload` and never answered it - so any add-on calling `downloads.download()` waited
 * forever on a promise that could not settle. There is now always exactly one answer, because
 * `GeckoResult.complete()` throws if called twice:
 *
 *  - pre-flight failure (unsupported scheme, storage permission missing on Android 9): return null
 *    from [start], which Gecko turns into an immediate `downloads.download is not supported` error;
 *  - HTTP / I/O failure after the fact: the returned [GeckoResult] is completed with null, which
 *    Gecko rejects the same way;
 *  - success: a `WebExtension.Download` created through `WebExtensionController.createDownload`
 *    (the public factory - `Download`'s own constructor is protected), progress pushed with
 *    `Download.update`, and `STATE_COMPLETE` / `STATE_INTERRUPTED` reported at the end so the
 *    add-on's `downloads.onChanged` fires.
 *
 * Known, documented limits: `saveAs` shows no file chooser (there is no UI
 * attached to a background script), and `downloads.pause` / `downloads.remove` issued *by* the
 * add-on are not observable - `Download.setDelegate` is package-private in GeckoView, so only our
 * own state transitions can be reported.
 */
internal class ExtensionDownloadRunner(private val core: BrowserCore) {

    private val nextId = AtomicInteger(1)
    private val controller get() = core.engine.runtime.webExtensionController

    fun start(ext: WebExtension, request: WebExtension.DownloadRequest): GeckoResult<WebExtension.DownloadInitData>? {
        val uri = request.request.uri
        if (!uri.startsWith("http://") && !uri.startsWith("https://")) {
            AppLog.w(TAG, "refused: unsupported scheme (${schemeHost(uri)})")
            return null
        }
        if (core.downloads.needsStoragePermission(core.app)) {
            // Android 9 only: writing to the public Downloads folder would fail outright, so say so
            // now instead of failing halfway through a file the add-on believes it started.
            AppLog.w(TAG, "refused: storage permission missing for ${ext.id}")
            return null
        }
        if (request.saveAs && EXTDBG) AppLog.d(EXTDBG_TAG, "saveAs ignored (no file chooser for a background script)")

        val out = GeckoResult<WebExtension.DownloadInitData>()
        val referrer = request.request.referrer.orEmpty()
        val startedAt = System.currentTimeMillis()
        val builder = WebRequest.Builder(uri).cacheMode(WebRequest.CACHE_MODE_NO_STORE)
        if (referrer.isNotBlank()) builder.referrer(referrer)

        if (EXTDBG) AppLog.d(EXTDBG_TAG, "download start ext=${ext.id} ${schemeHost(uri)}")
        try {
            GeckoWebExecutor(core.engine.runtime).fetch(builder.build()).accept({ response ->
                if (response == null) {
                    AppLog.w(TAG, "download fetch returned no response for ${ext.id}")
                    out.complete(null)
                } else {
                    handle(ext, request, response, referrer, startedAt, out)
                }
            }, { t ->
                AppLog.w(TAG, "download fetch failed for ${ext.id}", t)
                out.complete(null)
            })
        } catch (t: Throwable) {
            AppLog.w(TAG, "download fetch could not start for ${ext.id}", t)
            out.complete(null)
        }
        return out
    }

    /** Validates the response, picks the name, creates the download object, then writes the body. */
    private fun handle(
        ext: WebExtension,
        request: WebExtension.DownloadRequest,
        response: WebResponse,
        referrer: String,
        startedAt: Long,
        out: GeckoResult<WebExtension.DownloadInitData>,
    ) {
        val status = response.statusCode
        if (status !in 200..299 && !request.allowHttpErrors) {
            try { response.body?.close() } catch (_: Throwable) {}
            AppLog.w(TAG, "download failed: HTTP $status for ${ext.id}")
            out.complete(null)
            return
        }

        val header = { name: String -> response.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value }
        val serverMime = header("Content-Type")?.substringBefore(';')?.trim()
        val total = header("Content-Length")?.trim()?.toLongOrNull() ?: -1L
        val contentDisposition = header("Content-Disposition")
        val body = response.body
        if (body == null) {
            AppLog.w(TAG, "download failed: empty body for ${ext.id}")
            out.complete(null)
            return
        }

        val name = resolveName(request.filename, request.request.uri, contentDisposition, serverMime)
        val mime = DownloadTypes.normalizeMime(name, serverMime)
        val overwrite = request.conflictActionFlag == WebExtension.DownloadRequest.CONFLICT_ACTION_OVERWRITE

        // createDownload() and Download.update() are both @UiThread, so everything that touches them
        // runs on core.scope (Dispatchers.Main.immediate); only the byte copy leaves that dispatcher.
        core.scope.launch {
            val target = try {
                withContext(Dispatchers.IO) { openTarget(name, mime, overwrite) }
            } catch (t: Throwable) {
                try { body.close() } catch (_: Throwable) {}
                AppLog.w(TAG, "download could not open a destination for ${ext.id}", t)
                out.complete(null)
                return@launch
            }

            val download = try {
                controller.createDownload(nextId.getAndIncrement())
            } catch (t: Throwable) {
                AppLog.w(TAG, "createDownload failed for ${ext.id}", t)
                null
            }
            if (download == null) {
                try { body.close() } catch (_: Throwable) {}
                withContext(Dispatchers.IO) { target.discard(core.app) }
                out.complete(null)
                return@launch
            }

            out.complete(
                WebExtension.DownloadInitData(
                    download,
                    ExtensionDownloadInfo(
                        downloadState = WebExtension.Download.STATE_IN_PROGRESS,
                        received = 0L, total = total, fileName = target.name, mimeType = mime,
                        startedAt = startedAt, endedAt = null, errorReason = null, exists = false,
                        referrerUrl = referrer,
                    ),
                ),
            )
            if (EXTDBG) AppLog.d(EXTDBG_TAG, "download accepted ext=${ext.id} name=${target.name}")

            write(body, download, target, total, mime, startedAt, referrer)
        }
    }

    /** Streams the body to [target], pushing throttled progress to the add-on. */
    private suspend fun write(
        body: InputStream,
        download: WebExtension.Download,
        target: Target,
        total: Long,
        mime: String,
        startedAt: Long,
        referrer: String,
    ) {
        var written = 0L
        var lastPushed = 0L
        var failure: Throwable? = null
        try {
            withContext(Dispatchers.IO) {
                val buffer = ByteArray(BUFFER_SIZE)
                target.stream.use { dst ->
                    while (true) {
                        val n = body.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        dst.write(buffer, 0, n)
                        written += n
                        if (written - lastPushed >= PROGRESS_STEP) {
                            lastPushed = written
                            push(download, target.name, written, total, mime, startedAt, referrer, complete = false, error = null)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            failure = t
        } finally {
            try { body.close() } catch (_: Throwable) {}
        }

        if (failure == null) {
            val finalName = withContext(Dispatchers.IO) { target.commit(core.app, mime) }
            push(download, finalName, written, if (total > 0) total else written, mime, startedAt, referrer,
                complete = true, error = null)
            AppLog.i(TAG, "download finished ($written bytes)")
        } else {
            withContext(Dispatchers.IO) { target.discard(core.app) }
            val reason = if (failure is IOException) WebExtension.Download.INTERRUPT_REASON_NETWORK_FAILED
            else WebExtension.Download.INTERRUPT_REASON_FILE_FAILED
            push(download, target.name, written, total, mime, startedAt, referrer, complete = false, error = reason)
            // The add-on already holds its download id, so the interruption is reported through
            // downloads.onChanged - never by leaving the download "in progress" forever.
            AppLog.w(TAG, "download interrupted (${failure.javaClass.simpleName})")
        }
    }

    /** `Download.update` is @UiThread: hop back to core.scope and keep going (fire-and-forget). */
    private fun push(
        download: WebExtension.Download,
        name: String,
        received: Long,
        total: Long,
        mime: String,
        startedAt: Long,
        referrer: String,
        complete: Boolean,
        error: Int?,
    ) {
        core.scope.launch {
            val info = ExtensionDownloadInfo(
                downloadState = when {
                    complete -> WebExtension.Download.STATE_COMPLETE
                    error != null -> WebExtension.Download.STATE_INTERRUPTED
                    else -> WebExtension.Download.STATE_IN_PROGRESS
                },
                received = received,
                total = total,
                fileName = name,
                mimeType = mime,
                startedAt = startedAt,
                endedAt = if (complete || error != null) System.currentTimeMillis() else null,
                errorReason = error,
                exists = complete,
                referrerUrl = referrer,
            )
            try { download.update(info) } catch (t: Throwable) { AppLog.w(TAG, "download update failed", t) }
        }
    }

    // ------------------------------------------------------------------ destination

    private fun openTarget(fileName: String, mime: String, overwrite: Boolean): Target {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (overwrite) {
                // Only our own rows: never delete a file another app owns just because it matches.
                try {
                    core.app.contentResolver.delete(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${MediaStore.Downloads.DISPLAY_NAME}=?",
                        arrayOf(fileName),
                    )
                } catch (t: Throwable) {
                    AppLog.w(TAG, "overwrite delete failed", t)
                }
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)   // MediaStore de-duplicates "name (1).ext"
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = core.app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert failed")
            val stream = core.app.contentResolver.openOutputStream(uri, "w")
                ?: run { core.app.contentResolver.delete(uri, null, null); throw IOException("Cannot open output") }
            val name = core.app.contentResolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: fileName
            return Target(stream, name, uri, null)
        }

        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
        if (overwrite) File(dir, fileName).delete()
        val file = uniqueFile(dir, fileName)
        return Target(FileOutputStream(file), file.name, null, file.absolutePath)
    }

    /** Same rule as AppDownloadManager: "report.bin" -> "report (1).bin". */
    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        return f
    }

    // ------------------------------------------------------------------ naming

    /** Prefer what the add-on asked for, then Content-Disposition, then the URL, then the MIME type. */
    private fun resolveName(requested: String?, uri: String, contentDisposition: String?, serverMime: String?): String {
        requested?.let { basename(it) }?.takeIf { it.isNotBlank() }?.let { return it }
        val guessed = basename(URLUtil.guessFileName(uri, contentDisposition, serverMime))
        if (guessed.isBlank() || guessed == "." || guessed == "..") return "download"
        if (!guessed.contains('.') && serverMime != null) {
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(serverMime)
            if (ext != null) return "$guessed.$ext"
        }
        return guessed
    }

    /** A `filename` from an add-on is relative to the downloads directory: never let it escape it. */
    private fun basename(path: String): String {
        val last = path.replace('\\', '/').substringAfterLast('/').trim().trim(':')
        return if (last.isBlank() || last == "." || last == "..") "download" else last
    }

    /** Only scheme + host ever reaches the log (never a query string, token or path). */
    private fun schemeHost(url: String): String = try {
        val u = Uri.parse(url)
        "${u.scheme}://${u.host}"
    } catch (_: Throwable) { "invalid-url" }
}
