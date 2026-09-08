package app.multisession.browser.webview

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.IOException

/**
 * Serves local HTML projects and bundled assets from a secure https:// origin using
 * WebViewAssetLoader instead of file:// (no file-system access is granted to pages).
 *
 *  https://appassets.androidplatform.net/www/...         -> app/src/main/assets/www/   (standalone / HTML-to-APK mode)
 *  https://appassets.androidplatform.net/projects/<id>/  -> filesDir/projects/<id>/    (imported user projects)
 */
class LocalContentLoader(context: Context) {

    val projectsDir: File = File(context.filesDir, "projects").apply { mkdirs() }

    private val assetsHandler = WebViewAssetLoader.AssetsPathHandler(context)

    private val loader: WebViewAssetLoader = WebViewAssetLoader.Builder()
        .setDomain(DOMAIN)
        .addPathHandler(ASSETS_PATH, object : WebViewAssetLoader.PathHandler {
            override fun handle(path: String): WebResourceResponse? = assetsHandler.handle("www/$path")
        })
        .addPathHandler(PROJECTS_PATH, WebViewAssetLoader.InternalStoragePathHandler(context, projectsDir))
        .build()

    private val hasBundledApp: Boolean = try {
        context.assets.open("www/index.html").close(); true
    } catch (e: IOException) {
        false
    }

    /** Called from WebViewClient.shouldInterceptRequest (background thread). */
    fun intercept(uri: Uri): WebResourceResponse? = loader.shouldInterceptRequest(uri)

    /** When the repo ships assets/www/index.html the app behaves as an HTML-to-APK runtime with that page as home. */
    fun bundledAppUrl(): String? = if (hasBundledApp) "$BASE$ASSETS_PATH" + "index.html" else null

    companion object {
        const val DOMAIN = "appassets.androidplatform.net"
        const val BASE = "https://$DOMAIN"
        const val ASSETS_PATH = "/www/"
        const val PROJECTS_PATH = "/projects/"

        fun projectUrl(projectId: String, entry: String): String = "$BASE$PROJECTS_PATH$projectId/$entry"
    }
}
