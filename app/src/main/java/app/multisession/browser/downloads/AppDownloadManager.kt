package app.multisession.browser.downloads

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.URLUtil
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import app.multisession.browser.core.AppLog
import app.multisession.browser.core.BrowserCore
import app.multisession.browser.data.db.DownloadEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.GeckoWebExecutor
import org.mozilla.geckoview.WebRequest
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object DownloadStatus {
    const val PENDING = 0
    const val RUNNING = 1
    const val PAUSED = 2
    const val COMPLETED = 3
    const val FAILED = 4
    const val CANCELLED = 5

    fun isActive(s: Int) = s == PENDING || s == RUNNING
    fun isFinished(s: Int) = s == COMPLETED || s == FAILED || s == CANCELLED
}

/**
 * The app's own download manager (replaces the Android DownloadManager UI).
 *
 * Start: Gecko hands us the *response* of a navigation it cannot render (ContentDelegate.onExternalResponse). Its body
 * stream was fetched inside the tab's session context, so authenticated per-session downloads and blob:/data:
 * downloads work without the app ever touching cookies. The stream is copied to the public Downloads folder
 * (MediaStore on Android 10+, direct file on Android 9) while progress is written to Room + a StateFlow.
 *
 * Pause = stop reading and keep the partial file. Resume/Retry re-fetch through GeckoWebExecutor with a Range header.
 * Honest limitation: GeckoWebExecutor requests run in the DEFAULT cookie jar, not in a session context, so resuming a
 * download that needed the session's login fails (server answers 401/403 or an HTML login page) -> the item is marked
 * failed with an explanation and the user restarts it from the page. blob:/data: URLs cannot be re-fetched at all.
 */
class AppDownloadManager(private val core: BrowserCore) {

    /** Parsed view of a Gecko WebResponse used when starting a download. */
    class Request(val response: WebResponse) {
        val url: String get() = response.uri
        private val serverMime: String? = header("Content-Type")
        val contentDisposition: String? = header("Content-Disposition")
        val contentLength: Long = header("Content-Length")?.trim()?.toLongOrNull() ?: -1L
        val fileName: String = URLUtil.guessFileName(url, contentDisposition, serverMime?.substringBefore(';')?.trim()).let { guessed ->
            // guessFileName falls back to ".bin": prefer an extension derived from the (normalised) MIME type.
            if (guessed.endsWith(".bin")) {
                val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(DownloadTypes.normalizeMime(guessed, serverMime))
                if (ext != null && ext != "bin") guessed.removeSuffix(".bin") + "." + ext else guessed
            } else guessed
        }
        val mimeType: String = DownloadTypes.normalizeMime(fileName, serverMime)
        private fun header(name: String): String? = response.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    private val _downloads = MutableStateFlow<List<DownloadEntity>>(emptyList())
    val downloads: StateFlow<List<DownloadEntity>> = _downloads.asStateFlow()

    private val jobs = ConcurrentHashMap<String, Job>()
    private val activeInputs = ConcurrentHashMap<String, InputStream>()
    private val pauseRequested = ConcurrentHashMap.newKeySet<String>()
    private val cancelRequested = ConcurrentHashMap.newKeySet<String>()
    /** User removed these entries: any late publish from their still-running job must not resurrect them. */
    private val deletedIds = ConcurrentHashMap.newKeySet<String>()
    /** delete(id, deleteFile = false) on a RUNNING transfer: stop it but keep the partial file. */
    private val keepFileIds = ConcurrentHashMap.newKeySet<String>()

    val activeCount: Int get() = _downloads.value.count { DownloadStatus.isActive(it.status) }

    /**
     * v2.2.0-beta-3: pause/cancel used to be plain flags read BETWEEN reads - a stalled connection
     * blocked input.read() indefinitely, so the flags were never observed and cancel did nothing
     * (and a delete()d entry later resurrected when the read finally returned). Closing the body
     * stream from the control path unblocks read() immediately; the job then classifies the
     * resulting IOException from the flags.
     */
    private fun interrupt(id: String) {
        activeInputs[id]?.let { s -> try { s.close() } catch (_: Throwable) {} }
    }

    suspend fun load() {
        // Anything that was running when the process died is not running any more: make it resumable.
        core.repo.downloads.remap(listOf(DownloadStatus.PENDING, DownloadStatus.RUNNING), DownloadStatus.PAUSED, System.currentTimeMillis())
        _downloads.value = core.repo.downloads.getAll()
        AppLog.i(TAG, "Loaded ${_downloads.value.size} downloads")
    }

    /** Android 9 needs WRITE_EXTERNAL_STORAGE to save into the public Downloads folder. */
    fun needsStoragePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    /** Discards a response the user declined (closes the stream so Gecko can release the channel). */
    fun discard(req: Request) {
        try { req.response.body?.close() } catch (_: Throwable) {}
    }

    // ------------------------------------------------------------------ start / control

    fun start(req: Request, sessionId: String?, sourcePage: String?): DownloadEntity {
        val now = System.currentTimeMillis()
        val d = DownloadEntity(
            id = UUID.randomUUID().toString(), sessionId = sessionId, url = req.url, sourcePage = sourcePage,
            fileName = req.fileName, mimeType = req.mimeType, totalBytes = req.contentLength, downloadedBytes = 0,
            status = DownloadStatus.PENDING, contentUri = null, filePath = null, error = null, createdAt = now, updatedAt = now,
        )
        publish(d)
        val body = req.response.body
        if (body == null) { publish(d.copy(status = DownloadStatus.FAILED, error = "Empty response body")); return d }
        runStream(d, body, append = false)
        return d
    }

    fun pause(id: String) {
        val d = get(id) ?: return
        if (!DownloadStatus.isActive(d.status)) return
        pauseRequested.add(id)
        interrupt(id)   // a blocked read() must return now, not after the network recovers
    }

    fun cancel(id: String) {
        val d = get(id) ?: return
        if (DownloadStatus.isActive(d.status)) {
            cancelRequested.add(id)
            interrupt(id)   // the job deletes the partial file itself once its stream is closed
            return
        }
        // Paused: nothing is running -> remove the partial file right away. Finished states are
        // deliberately NOT touched: removing a finished entry is delete()'s job, and cancel must
        // never destroy a completed file (that produced "file does not exist" on open).
        if (d.status == DownloadStatus.PAUSED) {
            core.scope.launch(Dispatchers.IO) { deleteFile(d) ; publish(d.copy(status = DownloadStatus.CANCELLED, downloadedBytes = 0, contentUri = null, filePath = null, updatedAt = System.currentTimeMillis())) }
        }
    }

    /** Continues a paused/interrupted download with an HTTP Range request (falls back to a full restart when the server ignores it). */
    fun resume(id: String) {
        val d = get(id) ?: return
        if (d.status != DownloadStatus.PAUSED && d.status != DownloadStatus.FAILED) return
        if (!d.url.startsWith("http://") && !d.url.startsWith("https://")) {
            publish(d.copy(status = DownloadStatus.FAILED, error = "This download (${d.url.substringBefore(':')}: URL) can only be restarted from the page", updatedAt = System.currentTimeMillis()))
            return
        }
        val offset = if (d.downloadedBytes > 0 && (d.contentUri != null || d.filePath != null)) d.downloadedBytes else 0L
        publish(d.copy(status = DownloadStatus.PENDING, error = null, updatedAt = System.currentTimeMillis()))
        fetch(d, offset)
    }

    /** Starts over from byte 0 (failed / cancelled downloads). */
    fun retry(id: String) {
        val d = get(id) ?: return
        if (DownloadStatus.isActive(d.status)) return
        if (!d.url.startsWith("http://") && !d.url.startsWith("https://")) {
            publish(d.copy(status = DownloadStatus.FAILED, error = "This download can only be restarted from the page", updatedAt = System.currentTimeMillis()))
            return
        }
        core.scope.launch {
            withContext(Dispatchers.IO) { deleteFile(d) }
            val fresh = d.copy(status = DownloadStatus.PENDING, downloadedBytes = 0, contentUri = null, filePath = null, error = null, updatedAt = System.currentTimeMillis())
            publish(fresh)
            fetch(fresh, 0)
        }
    }

    /** Removes the history entry; optionally the file too. */
    fun delete(id: String, deleteFile: Boolean) {
        val d = get(id) ?: return
        deletedIds.add(id)   // tombstone: late publishes from a still-running job are dropped in publish()
        if (DownloadStatus.isActive(d.status)) {
            cancelRequested.add(id)
            if (!deleteFile) keepFileIds.add(id)
            // The file is NOT deleted here - the job owns the open output. It deletes (or un-pends,
            // keepFileIds) the file itself after interrupt() closes its stream. Deleting the file
            // while the job still wrote to it was what left completed entries pointing at nothing.
            interrupt(id)
        } else if (deleteFile) {
            core.scope.launch(Dispatchers.IO) { deleteFile(d) }
        }
        _downloads.value = _downloads.value.filterNot { it.id == id }
        core.persist { core.repo.downloads.delete(id) }
    }

    fun clearFinished() {
        val finished = listOf(DownloadStatus.COMPLETED, DownloadStatus.FAILED, DownloadStatus.CANCELLED)
        _downloads.value = _downloads.value.filterNot { it.status in finished }
        core.persist { core.repo.downloads.deleteWithStatus(finished) }
    }

    // ------------------------------------------------------------------ open / share

    fun fileUri(d: DownloadEntity): Uri? {
        d.contentUri?.let { return Uri.parse(it) }
        val path = d.filePath ?: return null
        val f = File(path)
        if (!f.exists()) return null
        return try { FileProvider.getUriForFile(core.app, core.app.packageName + ".fileprovider", f) } catch (t: Throwable) { null }
    }

    fun fileExists(d: DownloadEntity): Boolean = when {
        d.contentUri != null -> try { core.app.contentResolver.openFileDescriptor(Uri.parse(d.contentUri), "r")?.use { true } ?: false } catch (t: Throwable) { false }
        d.filePath != null -> File(d.filePath).exists()
        else -> false
    }

    fun openIntent(d: DownloadEntity): Intent? {
        val uri = fileUri(d) ?: return null
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, d.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun shareIntent(d: DownloadEntity): Intent? {
        val uri = fileUri(d) ?: return null
        return Intent(Intent.ACTION_SEND).setType(d.mimeType).putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_SUBJECT, d.fileName)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // ------------------------------------------------------------------ internals

    fun get(id: String): DownloadEntity? = _downloads.value.firstOrNull { it.id == id }

    private fun publish(d: DownloadEntity) {
        core.scope.launch {   // Main.immediate: safe from any thread
            if (d.id in deletedIds) return@launch   // v2.2.0-beta-3: a removed entry stays removed
            val cur = _downloads.value
            _downloads.value = if (cur.any { it.id == d.id }) cur.map { if (it.id == d.id) d else it } else listOf(d) + cur
            core.persist { core.repo.downloads.upsert(d) }
            // v2.1.7 (issue E): Android notifications for progress / finished / failed downloads.
            DownloadNotifier.onDownloadChanged(core.app, d)
        }
    }

    private fun fetch(d: DownloadEntity, offset: Long) {
        try {
            val builder = WebRequest.Builder(d.url).cacheMode(WebRequest.CACHE_MODE_NO_STORE)
            d.sourcePage?.let { builder.referrer(it) }
            if (offset > 0) builder.addHeader("Range", "bytes=$offset-")
            GeckoWebExecutor(core.engine.runtime).fetch(builder.build()).accept({ resp ->
                if (resp == null) { publish(d.copy(status = DownloadStatus.FAILED, error = "No response", updatedAt = System.currentTimeMillis())); return@accept }
                val status = resp.statusCode
                val type = resp.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value?.substringBefore(';')?.trim()?.lowercase()
                when {
                    status == 401 || status == 403 || (status == 200 && type == "text/html" && !d.mimeType.startsWith("text/html")) -> {
                        try { resp.body?.close() } catch (_: Throwable) {}
                        publish(d.copy(status = DownloadStatus.FAILED, error = "Sign-in required: resuming runs outside the session. Start the download again from the page.", updatedAt = System.currentTimeMillis()))
                    }
                    status == 206 && offset > 0 -> runStream(d, resp.body ?: return@accept, append = true)
                    status in 200..299 -> {
                        val len = resp.headers.entries.firstOrNull { it.key.equals("Content-Length", true) }?.value?.trim()?.toLongOrNull() ?: d.totalBytes
                        core.scope.launch {
                            withContext(Dispatchers.IO) { deleteFile(d) }
                            runStream(d.copy(downloadedBytes = 0, contentUri = null, filePath = null, totalBytes = len), resp.body ?: return@launch, append = false)
                        }
                    }
                    else -> {
                        try { resp.body?.close() } catch (_: Throwable) {}
                        publish(d.copy(status = DownloadStatus.FAILED, error = "Server answered HTTP $status", updatedAt = System.currentTimeMillis()))
                    }
                }
            }, { t -> publish(d.copy(status = DownloadStatus.FAILED, error = t?.message ?: "Network error", updatedAt = System.currentTimeMillis())) })
        } catch (t: Throwable) {
            publish(d.copy(status = DownloadStatus.FAILED, error = t.message ?: "Network error", updatedAt = System.currentTimeMillis()))
        }
    }

    private fun runStream(start: DownloadEntity, body: InputStream, append: Boolean) {
        jobs[start.id] = core.scope.launch(Dispatchers.IO) {
            // v2.2.0-beta-3: cancel/delete that arrived while the fetch was still in flight (no
            // stream to interrupt yet) - finish here, before creating an output to throw away.
            if (cancelRequested.remove(start.id) || deletedIds.contains(start.id)) {
                finishCancelled(start, start.downloadedBytes)
                return@launch
            }
            var d = start.copy(status = DownloadStatus.RUNNING, error = null, updatedAt = System.currentTimeMillis())
            publish(d)
            var out: OutputStream? = null
            var written = d.downloadedBytes
            activeInputs[start.id] = body
            try {
                d = openOutput(d, append)
                val stream = openStream(d, append)
                out = stream
                val buf = ByteArray(128 * 1024)
                var last = System.currentTimeMillis()
                written = d.downloadedBytes
                body.use { input ->
                    while (true) {
                        if (cancelRequested.remove(d.id) || deletedIds.contains(d.id)) {
                            stream.close(); out = null
                            finishCancelled(d, written)
                            return@launch
                        }
                        if (pauseRequested.remove(d.id)) {
                            stream.flush(); stream.close(); out = null
                            publish(d.copy(status = DownloadStatus.PAUSED, downloadedBytes = written, updatedAt = System.currentTimeMillis()))
                            return@launch
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        stream.write(buf, 0, n)
                        written += n
                        val now = System.currentTimeMillis()
                        if (now - last > 300) { last = now; d = d.copy(downloadedBytes = written, updatedAt = now); publish(d) }
                    }
                }
                stream.flush(); stream.close(); out = null
                d = finalizeFile(d.copy(downloadedBytes = written, totalBytes = if (d.totalBytes <= 0) written else d.totalBytes))
                publish(d.copy(status = DownloadStatus.COMPLETED, updatedAt = System.currentTimeMillis()))
                AppLog.i(TAG, "Download complete ${d.fileName} ($written bytes)")
            } catch (t: Throwable) {
                // interrupt() closes the blocked read() from pause/cancel/delete: classify by flags
                val cancelled = cancelRequested.remove(d.id) || deletedIds.contains(start.id)
                val paused = !cancelled && pauseRequested.remove(d.id)
                try { out?.flush() } catch (_: Throwable) {}
                try { out?.close() } catch (_: Throwable) {}
                out = null
                when {
                    cancelled -> finishCancelled(d, written)
                    paused -> publish(d.copy(status = DownloadStatus.PAUSED, downloadedBytes = written, updatedAt = System.currentTimeMillis()))
                    else -> {
                        AppLog.e(TAG, "Download failed ${d.fileName}", t)
                        publish(d.copy(status = DownloadStatus.PAUSED.takeIf { written > 0 && d.url.startsWith("http") } ?: DownloadStatus.FAILED,
                            error = t.message ?: "I/O error", updatedAt = System.currentTimeMillis()))
                    }
                }
            } finally {
                activeInputs.remove(start.id)
                jobs.remove(start.id)
            }
        }
    }

    /**
     * Terminal cancel path (observed in the loop or in the interrupted catch): honours keepFileIds
     * (entry removed but the partial file stays, un-pended so it is visible) or deletes the partial,
     * then publishes CANCELLED - which publish() drops for tombstoned (user-removed) entries.
     */
    private fun finishCancelled(d: DownloadEntity, written: Long) {
        if (keepFileIds.remove(d.id)) {
            d.contentUri?.let { u ->
                try {
                    core.app.contentResolver.update(Uri.parse(u), ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                } catch (_: Throwable) {}
            }
            publish(d.copy(status = DownloadStatus.CANCELLED, downloadedBytes = written, updatedAt = System.currentTimeMillis()))
        } else {
            deleteFile(d)
            publish(d.copy(status = DownloadStatus.CANCELLED, downloadedBytes = 0, contentUri = null, filePath = null, updatedAt = System.currentTimeMillis()))
        }
    }

    /** Creates the destination (MediaStore row / file) unless we append to an existing partial file. */
    private fun openOutput(d: DownloadEntity, append: Boolean): DownloadEntity {
        if (append && (d.contentUri != null || d.filePath != null)) return d
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, d.fileName)     // MediaStore de-duplicates ("name (1).ext") itself
                put(MediaStore.Downloads.MIME_TYPE, d.mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = core.app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("MediaStore insert failed")
            d.copy(contentUri = uri.toString())
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
            d.copy(filePath = uniqueFile(dir, d.fileName).absolutePath)
        }
    }

    private fun openStream(d: DownloadEntity, append: Boolean): OutputStream {
        d.contentUri?.let { return core.app.contentResolver.openOutputStream(Uri.parse(it), if (append) "wa" else "w") ?: throw IOException("Cannot open output") }
        return FileOutputStream(File(d.filePath!!), append)
    }

    private fun finalizeFile(d: DownloadEntity): DownloadEntity {
        d.contentUri?.let { u ->
            val uri = Uri.parse(u)
            core.app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            val name = core.app.contentResolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            return if (name != null) d.copy(fileName = name) else d
        }
        d.filePath?.let { p ->
            val f = File(p)
            try {
                @Suppress("DEPRECATION")
                core.app.getSystemService(DownloadManager::class.java).addCompletedDownload(f.name, f.name, true, d.mimeType, f.absolutePath, f.length(), false)
            } catch (_: Throwable) {}
            return d.copy(fileName = f.name)
        }
        return d
    }

    private fun deleteFile(d: DownloadEntity) {
        try {
            d.contentUri?.let { core.app.contentResolver.delete(Uri.parse(it), null, null) }
            d.filePath?.let { File(it).delete() }
        } catch (t: Throwable) {
            AppLog.w(TAG, "deleteFile failed", t)
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        if (!f.exists()) return f
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        while (f.exists()) { f = File(dir, "$base ($i)$ext"); i++ }
        return f
    }

    private companion object {
        const val TAG = "Downloads"
    }
}
