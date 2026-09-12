package app.multisession.browser.engine

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * URL scheme for local HTML content in GeckoView (replaces WebViewAssetLoader):
 *
 *  resource://android/assets/www/...   -> app/src/main/assets/www/   (bundled "HTML-to-APK" app; built into Gecko)
 *  file:///data/.../files/projects/<id>/... -> imported user projects in the app's private storage
 *
 * Security: Gecko treats every file:// document as its own origin (strict origin policy), and
 * [isAllowedLocalUri] is enforced in NavigationDelegate.onLoadRequest so the ONLY file:// URLs a
 * page can navigate to are inside the projects folder. Typed file: URLs are still refused by UrlUtils.
 */
class LocalContentLoader(context: Context) {

    val projectsDir: File = File(context.filesDir, "projects").apply { mkdirs() }
    private val projectsRootPath: String = projectsDir.canonicalPath + File.separator

    private val hasBundledApp: Boolean = try {
        context.assets.open("www/index.html").close(); true
    } catch (e: IOException) {
        false
    }

    /** When the repo ships assets/www/index.html the app behaves as an HTML-to-APK runtime with that page as home. */
    fun bundledAppUrl(): String? = if (hasBundledApp) BUNDLED_BASE + "index.html" else null

    fun projectUrl(projectId: String, entry: String): String =
        Uri.fromFile(File(File(projectsDir, projectId), entry)).toString()

    /** True for our bundled assets origin or a file inside the projects folder (never anything else on disk). */
    fun isAllowedLocalUri(url: String): Boolean {
        if (url.startsWith(BUNDLED_BASE)) return true
        if (!url.startsWith("file://")) return false
        return try {
            val path = Uri.parse(url).path ?: return false
            File(path).canonicalPath.startsWith(projectsRootPath)
        } catch (t: Throwable) {
            false
        }
    }

    companion object {
        const val BUNDLED_BASE = "resource://android/assets/www/"

        fun isLocalUrl(url: String?): Boolean =
            url != null && (url.startsWith(BUNDLED_BASE) || url.startsWith("file://"))
    }
}
