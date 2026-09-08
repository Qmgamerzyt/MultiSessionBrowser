package app.multisession.browser.webview

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.core.content.ContextCompat
import app.multisession.browser.core.AppLog
import app.multisession.browser.core.BrowserCore
import app.multisession.browser.core.UrlUtils
import app.multisession.browser.data.db.SessionEntity
import java.io.File
import java.net.URLDecoder

/**
 * Downloads via Android DownloadManager (system notification + progress).
 * The request carries the *session's* cookies so authenticated downloads work per session.
 */
class DownloadHandler(private val core: BrowserCore) {

    data class Request(
        val url: String,
        val userAgent: String?,
        val contentDisposition: String?,
        val mimeType: String?,
        val contentLength: Long,
    ) {
        val fileName: String get() = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val isBlob: Boolean get() = url.startsWith("blob:")
        val isData: Boolean get() = url.startsWith("data:")
    }

    class UnsupportedDownload(message: String) : Exception(message)

    /** Android 9 needs WRITE_EXTERNAL_STORAGE to save into the public Downloads folder. */
    fun needsStoragePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED

    fun enqueue(session: SessionEntity, req: Request): Result<String> = try {
        when {
            req.isBlob -> Result.failure(UnsupportedDownload("blob: URLs cannot be downloaded by Android DownloadManager"))
            req.isData -> saveDataUrl(req)
            !UrlUtils.isWebUrl(req.url) -> Result.failure(UnsupportedDownload("Unsupported download scheme"))
            else -> enqueueDownloadManager(session, req)
        }
    } catch (t: Throwable) {
        AppLog.e(TAG, "Download failed", t)
        Result.failure(t)
    }

    private fun enqueueDownloadManager(session: SessionEntity, req: Request): Result<String> {
        val dm = core.app.getSystemService(DownloadManager::class.java)
        val name = req.fileName
        val request = DownloadManager.Request(Uri.parse(req.url)).apply {
            setTitle(name)
            setDescription(UrlUtils.displayHost(req.url))
            req.mimeType?.takeIf { it.isNotBlank() }?.let { setMimeType(it) }
            req.userAgent?.takeIf { it.isNotBlank() }?.let { addRequestHeader("User-Agent", it) }
            // Per-session cookie jar -> authenticated downloads stay inside the session. Never logged.
            core.isolation.cookieManager(session).getCookie(req.url)?.let { addRequestHeader("Cookie", it) }
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
        }
        dm.enqueue(request)
        AppLog.i(TAG, "Download enqueued: $name (${req.contentLength} bytes)")
        return Result.success(name)
    }

    @Suppress("DEPRECATION")
    private fun saveDataUrl(req: Request): Result<String> {
        val comma = req.url.indexOf(',')
        if (comma < 0) return Result.failure(UnsupportedDownload("Malformed data: URL"))
        val meta = req.url.substring(5, comma)
        val payload = req.url.substring(comma + 1)
        val mime = meta.substringBefore(';').ifBlank { req.mimeType?.ifBlank { null } ?: "application/octet-stream" }
        val bytes = if (meta.contains(";base64")) Base64.decode(payload, Base64.DEFAULT)
        else URLDecoder.decode(payload, "UTF-8").toByteArray()
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
        val name = "download_${System.currentTimeMillis()}.$ext"
        val ctx = core.app
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return Result.failure(UnsupportedDownload("MediaStore insert failed"))
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val file = File(dir, name)
            file.writeBytes(bytes)
            ctx.getSystemService(DownloadManager::class.java)
                .addCompletedDownload(name, name, true, mime, file.absolutePath, bytes.size.toLong(), true)
        }
        AppLog.i(TAG, "data: URL saved as $name")
        return Result.success(name)
    }

    private companion object {
        const val TAG = "Downloads"
    }
}
