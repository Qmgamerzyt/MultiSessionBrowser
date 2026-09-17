package app.multisession.browser.downloads

import android.webkit.MimeTypeMap
import app.multisession.browser.R

/** File-type classification for the download list and for deciding how a finished download may be opened. */
enum class DownloadKind(val labelRes: Int, val iconRes: Int) {
    ARCHIVE(R.string.dl_kind_archive, R.drawable.ic_archive),
    APK(R.string.dl_kind_apk, R.drawable.ic_apk),
    PDF(R.string.dl_kind_pdf, R.drawable.ic_pdf),
    IMAGE(R.string.dl_kind_image, R.drawable.ic_image),
    VIDEO(R.string.dl_kind_video, R.drawable.ic_video),
    AUDIO(R.string.dl_kind_audio, R.drawable.ic_audio),
    DOCUMENT(R.string.dl_kind_document, R.drawable.ic_document),
    SPREADSHEET(R.string.dl_kind_spreadsheet, R.drawable.ic_document),
    PRESENTATION(R.string.dl_kind_presentation, R.drawable.ic_document),
    TEXT(R.string.dl_kind_text, R.drawable.ic_document),
    OTHER(R.string.dl_kind_other, R.drawable.ic_file),
}

object DownloadTypes {
    const val APK_MIME = "application/vnd.android.package-archive"

    private val byExtension = mapOf(
        "zip" to "application/zip", "rar" to "application/vnd.rar", "7z" to "application/x-7z-compressed",
        "tar" to "application/x-tar", "gz" to "application/gzip", "tgz" to "application/gzip", "bz2" to "application/x-bzip2", "xz" to "application/x-xz",
        "apk" to APK_MIME, "apks" to APK_MIME, "xapk" to "application/octet-stream",
        "pdf" to "application/pdf",
        "doc" to "application/msword", "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "odt" to "application/vnd.oasis.opendocument.text", "rtf" to "application/rtf",
        "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "ods" to "application/vnd.oasis.opendocument.spreadsheet", "csv" to "text/csv",
        "ppt" to "application/vnd.ms-powerpoint", "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation", "odp" to "application/vnd.oasis.opendocument.presentation",
        "txt" to "text/plain", "md" to "text/markdown", "json" to "application/json", "xml" to "text/xml", "html" to "text/html", "htm" to "text/html",
        "xpi" to "application/x-xpinstall", "epub" to "application/epub+zip",
        "mkv" to "video/x-matroska", "webm" to "video/webm", "mp4" to "video/mp4", "m4a" to "audio/mp4", "mp3" to "audio/mpeg", "ogg" to "audio/ogg", "opus" to "audio/ogg", "flac" to "audio/flac", "wav" to "audio/wav",
        "webp" to "image/webp", "avif" to "image/avif", "svg" to "image/svg+xml", "heic" to "image/heic",
    )

    /** Server MIME types are often missing or wrong ("application/octet-stream", "binary/octet-stream"): prefer the file extension when it is known. */
    fun normalizeMime(fileName: String, serverMime: String?): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val fromExt = byExtension[ext] ?: ext.takeIf { it.isNotEmpty() }?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
        val server = serverMime?.substringBefore(';')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it.contains('/') }
        val generic = server == null || server == "application/octet-stream" || server == "binary/octet-stream" || server == "application/unknown" || server == "application/force-download" || server == "application/download"
        return when {
            ext == "apk" -> APK_MIME
            generic -> fromExt ?: "application/octet-stream"
            server == "text/plain" && fromExt != null && fromExt != "text/plain" -> fromExt   // e.g. .apk / .zip served as text
            else -> server ?: fromExt ?: "application/octet-stream"
        }
    }

    fun kindOf(fileName: String, mime: String): DownloadKind {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val m = mime.lowercase()
        return when {
            m == APK_MIME || ext == "apk" || ext == "apks" || ext == "xapk" -> DownloadKind.APK
            m == "application/pdf" || ext == "pdf" -> DownloadKind.PDF
            ext in setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz") || m.contains("zip") || m.contains("rar") || m.contains("7z") || m.contains("tar") || m.contains("compressed") -> DownloadKind.ARCHIVE
            m.startsWith("image/") -> DownloadKind.IMAGE
            m.startsWith("video/") -> DownloadKind.VIDEO
            m.startsWith("audio/") -> DownloadKind.AUDIO
            ext in setOf("doc", "docx", "odt", "rtf") || m.contains("msword") || m.contains("wordprocessingml") || m.contains("opendocument.text") -> DownloadKind.DOCUMENT
            ext in setOf("xls", "xlsx", "ods", "csv") || m.contains("ms-excel") || m.contains("spreadsheetml") || m.contains("opendocument.spreadsheet") -> DownloadKind.SPREADSHEET
            ext in setOf("ppt", "pptx", "odp") || m.contains("ms-powerpoint") || m.contains("presentationml") || m.contains("opendocument.presentation") -> DownloadKind.PRESENTATION
            m.startsWith("text/") || m == "application/json" || m == "application/xml" -> DownloadKind.TEXT
            else -> DownloadKind.OTHER
        }
    }

    /** Never auto-executed: archives need an archive app, APKs go through the system installer only after an explicit confirmation. */
    fun requiresConfirmation(kind: DownloadKind): Boolean = kind == DownloadKind.APK
}
