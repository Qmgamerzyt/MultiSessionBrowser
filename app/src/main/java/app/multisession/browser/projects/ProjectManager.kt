package app.multisession.browser.projects

import android.content.Context
import android.net.Uri
import app.multisession.browser.core.AppLog
import app.multisession.browser.engine.LocalContentLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Local HTML "project" system. Each project is a folder under filesDir/projects/<id>/ with a
 * project.json ({name, entry}) and is opened by GeckoView via [LocalContentLoader] (file:// inside the app sandbox).
 *
 * APK generation cannot happen on the device (there is no Android SDK/compiler on a phone), so
 * "Build/Export" is honest: export the project as a ZIP and drop its contents into
 * app/src/main/assets/www/ of this repository -> GitHub Actions builds the APK.
 */
class ProjectManager(private val context: Context, private val localContent: LocalContentLoader) {

    data class Project(val id: String, val name: String, val entry: String, val dir: File, val fileCount: Int, val modifiedAt: Long, val url: String)

    private val root: File = File(context.filesDir, "projects").apply { mkdirs() }

    fun list(): List<Project> = root.listFiles()?.filter { it.isDirectory }?.mapNotNull { load(it) }
        ?.sortedByDescending { it.modifiedAt } ?: emptyList()

    fun get(id: String): Project? = File(root, id).takeIf { it.isDirectory }?.let { load(it) }

    /** The project whose folder contains [url], plus the file path relative to that folder ("" = the folder itself). */
    data class Located(val project: Project, val relPath: String)

    fun locate(url: String): Located? {
        if (!url.startsWith("file://")) return null
        val path = Uri.parse(url).path ?: return null
        return try {
            val prefix = root.canonicalPath + File.separator
            if (!path.startsWith(prefix)) return null
            val rest = path.removePrefix(prefix)
            val id = rest.substringBefore('/')
            if (id.isEmpty()) return null
            val p = get(id) ?: return null
            Located(p, rest.removePrefix(id).removePrefix("/"))
        } catch (t: Throwable) {
            null
        }
    }

    suspend fun importZip(uri: Uri, name: String): Project = withContext(Dispatchers.IO) {
        val dir = newDir()
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> extractZip(ZipInputStream(input), dir) }
                ?: throw IOException("Cannot open selected file")
            flattenSingleDirectory(dir)
            val entry = findEntry(dir) ?: throw IOException("No .html file found in the archive")
            writeMeta(dir, name.ifBlank { uri.lastPathSegment ?: "Project" }, entry)
            AppLog.i(TAG, "Project imported (${dir.name}) entry=$entry")
            load(dir)!!
        } catch (t: Throwable) {
            dir.deleteRecursively(); throw t
        }
    }

    suspend fun importHtml(uri: Uri, name: String): Project = withContext(Dispatchers.IO) {
        val dir = newDir()
        try {
            context.contentResolver.openInputStream(uri)?.use { input -> File(dir, "index.html").outputStream().use { input.copyTo(it) } }
                ?: throw IOException("Cannot open selected file")
            writeMeta(dir, name.ifBlank { "Imported page" }, "index.html")
            load(dir)!!
        } catch (t: Throwable) {
            dir.deleteRecursively(); throw t
        }
    }

    suspend fun createFromHtml(name: String, html: String): Project = withContext(Dispatchers.IO) {
        val dir = newDir()
        File(dir, "index.html").writeText(html)
        writeMeta(dir, name.ifBlank { "My page" }, "index.html")
        load(dir)!!
    }

    suspend fun delete(project: Project) = withContext(Dispatchers.IO) {
        project.dir.deleteRecursively()
        AppLog.i(TAG, "Project deleted ${project.id}")
    }

    /** Exports the project as a ZIP in cacheDir/exports (shared through FileProvider). */
    suspend fun exportZip(project: Project): File = withContext(Dispatchers.IO) {
        val out = File(File(context.cacheDir, "exports").apply { mkdirs() }, sanitize(project.name) + ".zip")
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            project.dir.walkTopDown().filter { it.isFile && it.name != META }.forEach { f ->
                zip.putNextEntry(ZipEntry(f.relativeTo(project.dir).path.replace(File.separatorChar, '/')))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        out
    }

    // ------------------------------------------------------------------ internals

    private fun newDir(): File = File(root, UUID.randomUUID().toString()).apply { mkdirs() }

    private fun load(dir: File): Project? {
        val meta = File(dir, META)
        val json = try {
            JSONObject(meta.readText())
        } catch (t: Throwable) {
            val entry = findEntry(dir) ?: return null
            JSONObject().put("name", dir.name).put("entry", entry)
        }
        val entry = json.optString("entry", "index.html")
        if (!File(dir, entry).isFile) return null
        val count = dir.walkTopDown().count { it.isFile && it.name != META }
        return Project(dir.name, json.optString("name", dir.name), entry, dir, count, dir.lastModified(), localContent.projectUrl(dir.name, entry))
    }

    private fun writeMeta(dir: File, name: String, entry: String) {
        File(dir, META).writeText(JSONObject().put("name", name).put("entry", entry).toString())
        dir.setLastModified(System.currentTimeMillis())
    }

    private fun extractZip(zip: ZipInputStream, dir: File) {
        val rootPath = dir.canonicalPath + File.separator
        var total = 0L
        var entry: ZipEntry? = zip.nextEntry
        while (entry != null) {
            val target = File(dir, entry.name)
            // Zip-slip protection: every entry must stay inside the project folder.
            if (!target.canonicalPath.startsWith(rootPath)) throw IOException("Illegal path in archive: ${entry.name}")
            if (entry.isDirectory) {
                target.mkdirs()
            } else {
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = zip.read(buf)
                        if (n <= 0) break
                        total += n
                        if (total > MAX_PROJECT_BYTES) throw IOException("Archive too large (limit ${MAX_PROJECT_BYTES / 1024 / 1024} MB)")
                        out.write(buf, 0, n)
                    }
                }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }

    /** "myapp.zip" often contains a single top folder; move its content up so index.html is at the root. */
    private fun flattenSingleDirectory(dir: File) {
        val children = dir.listFiles()?.filter { it.name != META && it.name != "__MACOSX" } ?: return
        val single = children.singleOrNull()?.takeIf { it.isDirectory } ?: return
        single.listFiles()?.forEach { child -> child.renameTo(File(dir, child.name)) }
        single.deleteRecursively()
    }

    private fun findEntry(dir: File): String? {
        if (File(dir, "index.html").isFile) return "index.html"
        dir.listFiles()?.firstOrNull { it.isFile && it.extension.lowercase() in setOf("html", "htm") }?.let { return it.name }
        return dir.walkTopDown().firstOrNull { it.isFile && it.name.equals("index.html", true) }
            ?.relativeTo(dir)?.path?.replace(File.separatorChar, '/')
    }

    private fun sanitize(name: String) = name.replace(Regex("[^A-Za-z0-9._-]+"), "_").ifBlank { "project" }

    private companion object {
        const val TAG = "Projects"
        const val META = "project.json"
        const val MAX_PROJECT_BYTES = 200L * 1024 * 1024
    }
}
