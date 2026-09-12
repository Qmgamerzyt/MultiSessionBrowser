package app.multisession.browser.engine

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.core.content.ContextCompat
import app.multisession.browser.core.AppLog
import app.multisession.browser.core.BrowserCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Downloads for GeckoView. Gecko hands us the *response* (ContentDelegate.onExternalResponse) whose
 * body stream was fetched inside the tab's session context - so authenticated, per-session
 * downloads work without ever touching cookies in app code, and blob:/data: downloads work too.
 * The stream is written to the public Downloads folder (MediaStore on Android 10+, direct file +
 * DownloadManager index entry on Android 9).
 */
class DownloadHandler(private val core: BrowserCore) {

    class Request(val response: WebResponse) {
        val url: String get() = response.uri
        val mimeType: String? = header("Content-Type")?.substringBefore(';')?.trim()?.takeIf { it.isNotBlank() }
        val contentDisposition: String? = header("Content-Disposition")
        val contentLength: Long = header("Content-Length")?.trim()?.toLongOrNull() ?: -1L
        val fileName: String = URLUtil.guessFileName(url, contentDisposition, mimeType).let { guessed ->
            if (guessed.endsWith(".bin") && mimeType != null) {
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
                if (ext != null) guessed.removeSuffix(".bin") + "." + ext else guessed
            } else guessed
        }
        private fun header(name: String): String? =
            response.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    }

    /** Android 9 needs WRITE_EXTERNAL_STORAGE to save into the public Downloads folder. */
    fun needsStoragePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    /** Streams the response body to Downloads. [onDone] is invoked on the main thread. */
    fun start(req: Request, onDone: (Result<String>) -> Unit): Job = core.scope.launch {
        val result = withContext(Dispatchers.IO) {
            try {
                val body = req.response.body ?: throw IOException("Empty response body")
                Result.success(save(body, req.fileName, req.mimeType ?: "application/octet-stream"))
            } catch (t: Throwable) {
                AppLog.e(TAG, "Download failed", t)
                Result.failure(t)
            }
        }
        onDone(result)
    }

    /** Discards a download the user declined (closes the stream so Gecko can release the channel). */
    fun cancel(req: Request) {
        try { req.response.body?.close() } catch (_: Throwable) {}
    }

    private fun save(body: InputStream, name: String, mime: String): String {
        val ctx = core.app
        body.use { input ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = ctx.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("MediaStore insert failed")
                try {
                    resolver.openOutputStream(uri)?.use { out -> copy(input, out) } ?: throw IOException("Cannot open output")
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                } catch (t: Throwable) {
                    resolver.delete(uri, null, null); throw t
                }
                val display = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                } ?: name
                AppLog.i(TAG, "Download saved: $display")
                return display
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
                val file = uniqueFile(dir, name)
                var total = 0L
                file.outputStream().use { out -> total = copy(input, out) }
                @Suppress("DEPRECATION")
                ctx.getSystemService(DownloadManager::class.java)
                    .addCompletedDownload(file.name, file.name, true, mime, file.absolutePath, total, true)
                AppLog.i(TAG, "Download saved: ${file.name} ($total bytes)")
                return file.name
            }
        }
    }

    private fun copy(input: InputStream, out: OutputStream): Long {
        val buf = ByteArray(128 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        out.flush()
        return total
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
