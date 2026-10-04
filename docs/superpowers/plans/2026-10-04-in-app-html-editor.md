# In-app HTML Project Editor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users edit every text file of a local HTML project on-device with a CodeMirror editor (tree drawer, syntax highlight, find, autosave, file create/rename/delete) and preview in a normal browser tab.

**Architecture:** A new `EditorActivity` hosts a system `android.webkit.WebView` that loads `file:///android_asset/editor/editor.html` (CodeMirror 5 bundle committed under `app/src/main/assets/editor/`), talking to a single `EditorBridge` object exposed as `window.Bridge` via `@JavascriptInterface`. All file I/O goes through the bridge with a canonical-path guard against the project folder; the native side owns the tree drawer, toolbar and flush points (preview/back), the JS side owns the 800 ms autosave debounce. Entry points: `ProjectsActivity` row menu + new-project flow, and the browser app-menu "Edit project" item while viewing a project page; preview opens `project.url` in the browser by delivering `SimpleListActivity.EXTRA_OPEN_URL` through a plain Intent that `BrowserActivity.handleIntent` now understands.

**Tech Stack:** Kotlin, AndroidX (DrawerLayout, RecyclerView, activity-result, lifecycleScope), Material dialogs, system WebView + `addJavascriptInterface`, CodeMirror 5.65.16 (MIT, bundled), `org.json`, existing `ProjectManager`/`LocalContentLoader`.

## Global Constraints

- **No local compile or test runner exists.** CI (`.github/workflows/build-apk.yml`, push-gated) is the ONLY compile check. Local verification per step: brace-balance diff vs HEAD (script below), `ET.parse` for every touched XML, targeted greps, `node --check` for JS.
- Balance script (run with `python3`, never `python`):

```bash
python3 - <<'EOF'
import subprocess, sys
def strip(src):
    out=[];i=0;n=len(src)
    while i<n:
        c=src[i]
        if c=='/' and i+1<n and src[i+1]=='*':
            j=src.find('*/',i+2); i=n if j<0 else j+2
        elif c=='/' and i+1<n and src[i+1]=='/':
            j=src.find('\n',i); i=n if j<0 else j
        elif c in '"\'':
            q=c;i+=1
            while i<n:
                if src[i]=='\\': i+=2; continue
                if src[i]==q: i+=1; break
                i+=1
        else:
            out.append(c); i+=1
    return ''.join(out)
def bal(src):
    s=strip(src); d={'{':0,'(':0,'[':0}; p={'}':'{',')':'(',']':'['}; m=0
    for ch in s:
        if ch in d: d[ch]+=1
        elif ch in p: d[p[ch]]-=1; m=min(m,d[p[ch]])
    return d,m
for f in sys.argv[1:]:
    cur=open(f).read()
    head=subprocess.run(['git','show',f'HEAD:{f}'],capture_output=True,text=True).stdout
    d1,m1=bal(cur); d0,m0=bal(head) if head else ({'{':0,'(':0,'[':0},0)
    ok=all(v==0 for v in d1.values()) and m1==0 and d1==d0
    print(('OK  ' if ok else 'FAIL'), f, d1, m1)
EOF
```
Save the script once by running the heredoc with `cat > /tmp/opencode/balance.py <<'EOF'` instead of `python3 - <<'EOF'`, then invoke it as `python3 /tmp/opencode/balance.py <file>...`. Expectation: every line `OK`, all-zero deltas.

- **No new try/catch** beyond mirroring existing same-path patterns: IO actions inside `lifecycleScope.launch` wrapped exactly like `ProjectsActivity` import/export (`catch (t: Throwable) { AppLog.e(...); Snackbar/Toast }`), and the bridge's system-boundary calls. Canonical-path guards copy `LocalContentLoader.isAllowedLocalUri`'s shape.
- **File policy (verbatim from spec):** editable extensions `html, htm, css, js, mjs, json, txt, md, svg, xml` AND strict-UTF-8 content; `project.json` listed but read-only (never saved/renamed/deleted); files > **2 MB** refused with toast.
- **Autosave:** JS debounce **800 ms**; flush points = Preview, back-press, and every file switch (inside JS `loadFile`).
- **Editor host:** system WebView, single URL `file:///android_asset/editor/editor.html`, interface name **`Bridge`** (only `@JavascriptInterface`-annotated methods visible), all non-asset navigations refused in `shouldOverrideUrlLoading`.
- Version stays `2.1.10` / versionCode `18`; new KDocs carry `(v2.1.10, Plan 3)`; commits `feat:`/`chore:`/`docs:` style; git identity warns cosmetically (root) — ignore.
- CodeMirror version pinned: **5.65.16**.

---

### Task 1: Bundle CodeMirror 5 + the editor page (assets only, no Kotlin yet)

**Files:**
- Create: `app/src/main/assets/editor/codemirror.min.js`, `codemirror.min.css`, `material-darker.min.css`, `dialog.css`
- Create: `app/src/main/assets/editor/mode/xml/xml.min.js`, `mode/javascript/javascript.min.js`, `mode/css/css.min.js`, `mode/htmlmixed/htmlmixed.min.js`
- Create: `app/src/main/assets/editor/addon/dialog/dialog.min.js`, `addon/search/searchcursor.min.js`, `addon/search/search.min.js`
- Create: `app/src/main/assets/editor/editor.html`

**Interfaces:**
- Consumes: nothing (pure assets).
- Produces: JS globals on the editor page — `loadFile(path: string)`, `flushSave(): void`, `find(): void`; expects injected `window.Bridge` with `openPath(path): string` (JSON `{ok, content|error}`), `save(path, content): "ok"|"err"`, `onDirty(bool): void`.

- [ ] **Step 1: Download CodeMirror 5.65.16 dist files**

```bash
mkdir -p app/src/main/assets/editor/mode/xml app/src/main/assets/editor/mode/javascript \
         app/src/main/assets/editor/mode/css app/src/main/assets/editor/mode/htmlmixed \
         app/src/main/assets/editor/addon/dialog app/src/main/assets/editor/addon/search \
         app/src/main/assets/editor/theme
B=https://cdnjs.cloudflare.com/ajax/libs/codemirror/5.65.16
curl -fLo app/src/main/assets/editor/codemirror.min.js  $B/codemirror.min.js
curl -fLo app/src/main/assets/editor/codemirror.min.css $B/codemirror.min.css
curl -fLo app/src/main/assets/editor/material-darker.min.css $B/theme/material-darker.min.css
curl -fLo app/src/main/assets/editor/mode/xml/xml.min.js               $B/mode/xml/xml.min.js
curl -fLo app/src/main/assets/editor/mode/javascript/javascript.min.js $B/mode/javascript/javascript.min.js
curl -fLo app/src/main/assets/editor/mode/css/css.min.js               $B/mode/css/css.min.js
curl -fLo app/src/main/assets/editor/mode/htmlmixed/htmlmixed.min.js   $B/mode/htmlmixed/htmlmixed.min.js
curl -fLo app/src/main/assets/editor/addon/dialog/dialog.min.js $B/addon/dialog/dialog.min.js
curl -fLo app/src/main/assets/editor/dialog.css                $B/addon/dialog/dialog.css
curl -fLo app/src/main/assets/editor/addon/search/searchcursor.min.js $B/addon/search/searchcursor.min.js
curl -fLo app/src/main/assets/editor/addon/search/search.min.js       $B/addon/search/search.min.js
```

Expected: 11 files, each HTTP 200 (`curl -f` fails loudly otherwise).

- [ ] **Step 2: Syntax-check every downloaded JS**

Run: `find app/src/main/assets/editor -name '*.js' -exec node --check {} \;`
Expected: no output (exit 0 for all). A parse error means a truncated download — re-curl that file.

- [ ] **Step 3: Write `editor.html`**

```html
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Editor</title>
<link rel="stylesheet" href="codemirror.min.css">
<link rel="stylesheet" href="material-darker.min.css">
<link rel="stylesheet" href="dialog.css">
<style>
  html, body { height: 100%; margin: 0; background: #1e1e1e; }
  #editor, .CodeMirror { height: 100%; font-size: 14px; }
  #empty { display: none; padding: 24px; font: 14px sans-serif; color: #9e9e9e; }
</style>
</head>
<body>
<div id="editor"></div>
<div id="empty"></div>
<script src="codemirror.min.js"></script>
<script src="mode/xml/xml.min.js"></script>
<script src="mode/javascript/javascript.min.js"></script>
<script src="mode/css/css.min.js"></script>
<script src="mode/htmlmixed/htmlmixed.min.js"></script>
<script src="addon/dialog/dialog.min.js"></script>
<script src="addon/search/searchcursor.min.js"></script>
<script src="addon/search/search.min.js"></script>
<script>
"use strict";
var Bridge = window.Bridge;
var applying = false;
var current = null;
var dirty = false;
var timer = null;
var cm = CodeMirror(document.getElementById("editor"), {
  lineNumbers: true,
  theme: "material-darker",
  indentUnit: 2,
  extraKeys: { "Ctrl-F": "findPersistent", "Cmd-F": "findPersistent" }
});

function modeFor(path) {
  if (/\.html?$/.test(path)) return "htmlmixed";
  if (/\.(js|mjs)$/.test(path)) return "javascript";
  if (/\.css$/.test(path)) return "css";
  if (/\.(xml|svg)$/.test(path)) return "xml";
  if (/\.json$/.test(path)) return "javascript";
  return "text/plain";
}

function setDirty(d) {
  if (d === dirty) return;
  dirty = d;
  Bridge.onDirty(d);
}

// Synchronous flush: called from the change-debounce AND from Kotlin before
// preview/back/file-switch (evaluateJavascript runs it to completion first).
function flushSave() {
  if (timer !== null) { clearTimeout(timer); timer = null; }
  if (current !== null && dirty) {
    Bridge.save(current, cm.getValue());
    // Failure already toasted by the bridge; clear dirty so a broken disk
    // does not re-toast on every flush. The next keystroke re-arms autosave.
    setDirty(false);
  }
}

function scheduleSave() {
  if (timer !== null) clearTimeout(timer);
  timer = setTimeout(function () { timer = null; flushSave(); }, 800);
}

cm.on("change", function () {
  if (applying || current === null) return;
  setDirty(true);
  scheduleSave();
});

function loadFile(path) {
  flushSave();
  var res = JSON.parse(Bridge.openPath(path));
  var empty = document.getElementById("empty");
  if (!res.ok) {
    applying = true;
    current = null;
    cm.setValue("");
    cm.clearHistory();
    applying = false;
    empty.textContent = res.error;
    empty.style.display = "block";
    return;
  }
  empty.style.display = "none";
  current = path;
  applying = true;
  cm.setOption("mode", modeFor(path));
  cm.setValue(res.content);
  cm.clearHistory();
  applying = false;
  setDirty(false);
  cm.focus();
}

// Called from Kotlin (evaluateJavascript):
window.loadFile = function (path) { loadFile(path); };
window.flushSave = function () { flushSave(); };
window.find = function () { cm.execCommand("findPersistent"); };
</script>
</body>
</html>
```

- [ ] **Step 4: Verify**

Run: `node --check` does not cover HTML; extract and check the inline script:
`python3 -c "import re,pathlib; s=pathlib.Path('app/src/main/assets/editor/editor.html').read_text(); print('\n'.join(re.findall(r'<script>(.*?)</script>', s, re.S)))" > /tmp/opencode/editor-inline.js && node --check /tmp/opencode/editor-inline.js`
Expected: exit 0. Also `ls -R app/src/main/assets/editor | head -30` — all 12 files present.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/assets/editor
git commit -m "chore: bundle CodeMirror 5.65.16 + editor page (Plan 3)"
```

---

### Task 2: EditorBridge (file core + JS interface) and its strings

**Files:**
- Create: `app/src/main/java/app/multisession/browser/projects/editor/EditorBridge.kt`
- Modify: `app/src/main/res/values/strings.xml` (append before `</resources>`)

**Interfaces:**
- Consumes: `ProjectManager.Project(id, name, entry, dir, ...)` (public `dir: File`), `R.string.*` added in this task, `AppLog`.
- Produces (Task 3 depends on these exact names):
  - `class EditorBridge(context: Context, project: ProjectManager.Project, dirtyListener: (Boolean) -> Unit)`
  - `data class TreeEntry(val path: String, val dir: Boolean, val size: Long)`
  - `fun list(): List<TreeEntry>` — every file/dir under the project root, `path` uses `/` separators, `__MACOSX` skipped
  - `fun open(rel: String): JSONObject` — `{ok:true, content}` or `{ok:false, error:<localized msg>}`; strict-UTF-8, allowlist, 2 MB; `project.json` readable (view-only)
  - `fun create(rel: String): Boolean`, `fun rename(from: String, to: String): Boolean`, `fun delete(rel: String): Boolean` — plain methods for Kotlin callers (never toast; caller does)
  - `@JavascriptInterface fun openPath(rel: String): String` (JSON, toasts on failure), `@JavascriptInterface fun save(rel: String, content: String): String` (`"ok"`/`"err"`, atomic tmp+rename, toasts on failure), `@JavascriptInterface fun onDirty(dirty: Boolean)` (posts `dirtyListener` on main)
  - Listener guarantee: `dirtyListener` is always invoked on the main thread.
  - Strings added: `editor_binary`, `editor_too_large`, `editor_bad_path`, `editor_save_failed`

- [ ] **Step 1: Add the four strings**

In `app/src/main/res/values/strings.xml`, immediately before the closing `</resources>`:

```xml
    <!-- v2.1.10 (Plan 3): in-app project editor -->
    <string name="editor_binary">Not an editable text file</string>
    <string name="editor_too_large">File is larger than 2 MB</string>
    <string name="editor_bad_path">Invalid file path</string>
    <string name="editor_save_failed">Could not save the file</string>
```

- [ ] **Step 2: Write `EditorBridge.kt`**

```kotlin
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
```

- [ ] **Step 3: Verify**

```bash
python3 /tmp/opencode/balance.py app/src/main/java/app/multisession/browser/projects/editor/EditorBridge.kt
```
(balance script from Global Constraints, saved once — see the save command there; for this NEW file expect `OK` with zero deltas: `git show HEAD:<path>` is empty and the script treats that as all-zero HEAD.)
XML check: `python3 -c "import xml.etree.ElementTree as ET; ET.parse('app/src/main/res/values/strings.xml'); print('XML OK')"`
Greps: `grep -c "editor_binary\|editor_too_large\|editor_bad_path\|editor_save_failed" app/src/main/res/values/strings.xml` → ≥ 4; `grep -n "@JavascriptInterface" .../EditorBridge.kt` → 3 hits.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/app/multisession/browser/projects/editor/EditorBridge.kt app/src/main/res/values/strings.xml
git commit -m "feat: project file bridge for the in-app editor (Plan 3)"
```

---

### Task 3: EditorActivity + layouts + menu + manifest

**Files:**
- Create: `app/src/main/java/app/multisession/browser/projects/editor/EditorActivity.kt`
- Create: `app/src/main/res/layout/activity_editor.xml`
- Create: `app/src/main/res/layout/item_editor_row.xml`
- Create: `app/src/main/res/menu/menu_editor.xml`
- Modify: `app/src/main/AndroidManifest.xml` (after the `ProjectsActivity` block, lines ~89-93)
- Modify: `app/src/main/res/values/strings.xml` (append before `</resources>`)

**Interfaces:**
- Consumes (exact): `EditorBridge(context, project, dirtyListener = {Boolean})`, `EditorBridge.list()`, `EditorBridge.TreeEntry(path, dir, size)`, `EditorBridge.create/rename/delete`, `ProjectManager.get(id)` / `Project.dir` / `Project.entry` / `Project.url`, `BrowserApp.core()`.
- Produces (Task 4 depends on these exact names):
  - `class EditorActivity : AppCompatActivity()`
  - `companion object { const val EXTRA_PROJECT_ID = "app.multisession.browser.EDITOR_PROJECT"; const val EXTRA_FILE = "app.multisession.browser.EDITOR_FILE" }`
  - `R.layout.activity_editor` (ids: `toolbar`, `webView`, `emptyView`, `drawerLayout`, `drawerTitle`, `drawerList`), `R.layout.item_editor_row` (ids: `rowIcon`, `rowName`), `R.menu.menu_editor` (ids: `action_new_file`, `action_find`, `action_preview`)
  - Strings: `edit` (Task 4 also uses it), `editor_preview`, `editor_new_file`, `editor_new_file_hint`, `editor_rename`, `editor_find`, `editor_op_failed`
  - Manifest activity `.projects.editor.EditorActivity` (`exported=false`, `parentActivityName=.ui.browser.BrowserActivity`, `windowSoftInputMode=adjustResize`)

- [ ] **Step 1: Add strings**

Before `</resources>` in `strings.xml`:

```xml
    <string name="edit">Edit</string>
    <string name="editor_preview">Preview</string>
    <string name="editor_new_file">New file</string>
    <string name="editor_new_file_hint">Path inside the project, e.g. style.css</string>
    <string name="editor_rename">Rename</string>
    <string name="editor_find">Find in file</string>
    <string name="editor_op_failed">Could not update the project</string>
```

- [ ] **Step 2: Write the three res files**

`app/src/main/res/layout/activity_editor.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.drawerlayout.widget.DrawerLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/drawerLayout"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical">

        <com.google.android.material.appbar.MaterialToolbar
            android:id="@+id/toolbar"
            android:layout_width="match_parent"
            android:layout_height="?attr/actionBarSize"
            android:background="?attr/colorSurface"
            app:navigationIcon="@drawable/ic_menu"
            app:titleTextColor="?attr/colorOnSurface" />

        <FrameLayout
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1">

            <WebView
                android:id="@+id/webView"
                android:layout_width="match_parent"
                android:layout_height="match_parent" />

            <TextView
                android:id="@+id/emptyView"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_gravity="center"
                android:gravity="center"
                android:padding="32dp"
                android:textAppearance="?attr/textAppearanceBodyLarge"
                android:textColor="?attr/colorOnSurfaceVariant"
                android:visibility="gone" />
        </FrameLayout>
    </LinearLayout>

    <LinearLayout
        android:layout_width="280dp"
        android:layout_height="match_parent"
        android:layout_gravity="start"
        android:background="?attr/colorSurface"
        android:orientation="vertical">

        <TextView
            android:id="@+id/drawerTitle"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:padding="16dp"
            android:textAppearance="?attr/textAppearanceTitleMedium" />

        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/drawerList"
            android:layout_width="match_parent"
            android:layout_height="match_parent" />
    </LinearLayout>
</androidx.drawerlayout.widget.DrawerLayout>
```

`app/src/main/res/layout/item_editor_row.xml` (indent applied at bind time via `itemView` padding):

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:background="?attr/selectableItemBackground"
    android:gravity="center_vertical"
    android:minHeight="44dp"
    android:orientation="horizontal"
    android:paddingEnd="16dp">

    <ImageView
        android:id="@+id/rowIcon"
        android:layout_width="20dp"
        android:layout_height="20dp"
        android:contentDescription="@null"
        android:src="@drawable/ic_document"
        app:tint="?attr/colorOnSurfaceVariant" />

    <TextView
        android:id="@+id/rowName"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="12dp"
        android:layout_weight="1"
        android:ellipsize="middle"
        android:singleLine="true"
        android:textAppearance="?attr/textAppearanceBodyLarge" />
</LinearLayout>
```

`app/src/main/res/menu/menu_editor.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<menu xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">
    <item android:id="@+id/action_new_file" android:icon="@drawable/ic_add" android:title="@string/editor_new_file" app:showAsAction="always" />
    <item android:id="@+id/action_find" android:icon="@drawable/ic_search" android:title="@string/editor_find" app:showAsAction="always" />
    <item android:id="@+id/action_preview" android:icon="@drawable/ic_open_in_new" android:title="@string/editor_preview" app:showAsAction="ifRoom" />
</menu>
```

- [ ] **Step 3: Register the activity in the manifest**

Insert after the `ProjectsActivity` block (after line 93 of `app/src/main/AndroidManifest.xml`):

```xml
        <activity
            android:name=".projects.editor.EditorActivity"
            android:exported="false"
            android:label="@string/local_projects"
            android:parentActivityName=".ui.browser.BrowserActivity"
            android:windowSoftInputMode="adjustResize" />
```

- [ ] **Step 4: Write `EditorActivity.kt` (complete, final — no follow-up cleanup needed)**

```kotlin
package app.multisession.browser.projects.editor

import android.content.Intent
import android.os.Bundle
import androidx.core.view.GravityCompat
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.multisession.browser.BrowserApp
import app.multisession.browser.R
import app.multisession.browser.projects.ProjectManager
import app.multisession.browser.ui.browser.BrowserActivity
import app.multisession.browser.ui.library.SimpleListActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * In-app editor for local HTML projects (v2.1.10, Plan 3).
 *
 * Hosts the system WebView on the bundled CodeMirror page (assets/editor/editor.html) and
 * bridges it to disk through [EditorBridge] (`window.Bridge`). The native side owns the file
 * tree drawer, the flush points (Preview / back), and rotation state; the JS side owns the
 * 800 ms autosave debounce. Preview delivers [SimpleListActivity.EXTRA_OPEN_URL] to
 * [BrowserActivity] so the browser opens the page while the editor stays on the back stack.
 */
class EditorActivity : AppCompatActivity() {

    private val core get() = BrowserApp.core()
    private var project: ProjectManager.Project? = null
    private lateinit var bridge: EditorBridge
    private lateinit var webView: WebView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var toolbar: MaterialToolbar
    private val rowAdapter = RowAdapter()
    private val expanded = mutableSetOf<String>()
    private var entries: List<EditorBridge.TreeEntry> = emptyList()
    private var currentPath: String? = null
    private var isDirty = false
    private var seeded = false
    private var pageLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        drawerLayout = findViewById(R.id.drawerLayout)
        toolbar.setNavigationOnClickListener { drawerLayout.openDrawer(GravityCompat.START) }
        val drawerList = findViewById<RecyclerView>(R.id.drawerList)
        drawerList.layoutManager = LinearLayoutManager(this)
        drawerList.adapter = rowAdapter

        val p = core.projects.get(intent.getStringExtra(EXTRA_PROJECT_ID) ?: "")
        if (p == null) { finish(); return }
        project = p
        toolbar.title = p.name
        findViewById<TextView>(R.id.drawerTitle).text = p.name
        bridge = EditorBridge(this, p) { dirty ->
            isDirty = dirty
            updateSubtitle()
        }
        currentPath = savedInstanceState?.getString(STATE_PATH)
            ?: intent.getStringExtra(EXTRA_FILE)?.takeIf { it.isNotBlank() } ?: p.entry
        updateSubtitle()

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !request.url.toString().startsWith("file:///android_asset/editor/")

            override fun onPageFinished(view: WebView, url: String) {
                if (pageLoaded) return
                pageLoaded = true
                loadInJs(currentPath ?: p.entry)
            }
        }
        webView.addJavascriptInterface(bridge, "Bridge")
        webView.loadUrl(EDITOR_URL)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leaveEditor()
        })
        refreshTree()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PATH, currentPath)
    }

    override fun onDestroy() {
        // onCreate can finish() before webView is bound (unknown project id) - guard the lateinit.
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- toolbar

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_editor, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_new_file -> { newFileDialog(); true }
        R.id.action_find -> { webView.evaluateJavascript("find()", null); true }
        R.id.action_preview -> { preview(); true }
        else -> super.onOptionsItemSelected(item)
    }

    /** Flush, then hand the project URL to the browser; this activity stays on the back stack. */
    private fun preview() {
        webView.evaluateJavascript("flushSave()") {
            val p = project ?: return@evaluateJavascript
            startActivity(
                Intent(this, BrowserActivity::class.java)
                    .putExtra(SimpleListActivity.EXTRA_OPEN_URL, p.url)
            )
        }
    }

    private fun leaveEditor() {
        if (!pageLoaded || !isDirty) { finish(); return }
        webView.evaluateJavascript("flushSave()") { finish() }
    }

    private fun updateSubtitle() {
        val path = currentPath ?: return
        toolbar.subtitle = if (isDirty) "$path \u2022" else path
    }

    // ---------------------------------------------------------------- editor page plumbing

    private fun loadInJs(path: String) {
        currentPath = path
        updateSubtitle()
        webView.evaluateJavascript("loadFile(" + JSONObject.quote(path) + ")", null)
    }

    // ---------------------------------------------------------------- file tree

    private fun refreshTree() {
        lifecycleScope.launch {
            val fresh = withContext(Dispatchers.IO) { bridge.list() }
            entries = fresh
            if (!seeded) { fresh.filter { it.dir }.forEach { expanded += it.path }; seeded = true }
            rowAdapter.submit(flatten())
        }
    }

    private fun flatten(): List<TreeRow> {
        val byParent = entries.groupBy { it.path.substringBeforeLast('/', "") }
        val out = mutableListOf<TreeRow>()
        fun emit(parent: String, depth: Int) {
            val kids = byParent[parent] ?: return
            kids.sortedWith(compareBy({ !it.dir }, { it.path.substringAfterLast('/') })).forEach { e ->
                out += TreeRow(e.path, e.path.substringAfterLast('/'), depth, e.dir)
                if (e.dir && expanded.contains(e.path)) emit(e.path, depth + 1)
            }
        }
        emit("", 0)
        return out
    }

    private fun onRowClick(row: TreeRow) {
        if (row.isDir) {
            if (!expanded.remove(row.path)) expanded.add(row.path)
            rowAdapter.submit(flatten())
        } else {
            drawerLayout.closeDrawer(GravityCompat.START)
            loadInJs(row.path)
        }
    }

    private fun onRowLongClick(row: TreeRow) {
        if (row.isDir) return
        val actions = arrayOf(getString(R.string.editor_rename), getString(R.string.delete))
        MaterialAlertDialogBuilder(this).setTitle(row.path).setItems(actions) { _, which ->
            when (which) {
                0 -> renameDialog(row.path)
                1 -> deleteDialog(row.path)
            }
        }.show()
    }

    private fun newFileDialog() {
        val input = EditText(this).apply { hint = getString(R.string.editor_new_file_hint) }
        MaterialAlertDialogBuilder(this).setTitle(R.string.editor_new_file).setView(input)
            .setPositiveButton(R.string.create) { _, _ ->
                val path = input.text.toString().trim().removePrefix("/")
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { bridge.create(path) }
                    if (ok) { refreshTree(); loadInJs(path) }
                    else Snackbar.make(toolbar, R.string.editor_op_failed, Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renameDialog(path: String) {
        val input = EditText(this).apply { setText(path) }
        MaterialAlertDialogBuilder(this).setTitle(R.string.editor_rename).setView(input)
            .setPositiveButton(R.string.editor_rename) { _, _ ->
                val to = input.text.toString().trim().removePrefix("/")
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { bridge.rename(path, to) }
                    if (ok) {
                        refreshTree()
                        if (currentPath == path) loadInJs(to)
                    } else Snackbar.make(toolbar, R.string.editor_op_failed, Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteDialog(path: String) {
        MaterialAlertDialogBuilder(this).setTitle(R.string.delete).setMessage(path)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { bridge.delete(path) }
                    if (ok) {
                        refreshTree()
                        val entry = project?.entry ?: return@launch
                        if (currentPath == path) loadInJs(entry)
                    } else Snackbar.make(toolbar, R.string.editor_op_failed, Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- tree rows

    private data class TreeRow(val path: String, val name: String, val depth: Int, val isDir: Boolean)

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {
        private var data: List<TreeRow> = emptyList()

        fun submit(rows: List<TreeRow>) { data = rows; notifyDataSetChanged() }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_editor_row, parent, false))

        override fun getItemCount() = data.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = data[position]
            holder.name.text = row.name
            holder.icon.setImageResource(if (row.isDir) R.drawable.ic_folder else R.drawable.ic_document)
            holder.itemView.setPadding(
                row.dp(), holder.itemView.paddingTop,
                holder.itemView.paddingEnd, holder.itemView.paddingBottom
            )
            holder.itemView.setOnClickListener { onRowClick(row) }
            holder.itemView.setOnLongClickListener { onRowLongClick(row); true }
        }

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val icon: ImageView = v.findViewById(R.id.rowIcon)
            val name: TextView = v.findViewById(R.id.rowName)
        }
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PROJECT_ID = "app.multisession.browser.EDITOR_PROJECT"
        const val EXTRA_FILE = "app.multisession.browser.EDITOR_FILE"
        private const val EDITOR_URL = "file:///android_asset/editor/editor.html"
        private const val STATE_PATH = "editor_path"
    }
}
```

Notes on details that are easy to get wrong:
- `row.dp()` expands to `((row.depth * 16) + 8).dp()` — write it as `((row.depth * 16) + 8).dp()` at the call site.
- `onPageFinished` may fire for sub-resources; the `pageLoaded` flag makes `loadInJs` run exactly once.
- `addJavascriptInterface` is called **after** `webViewClient` is set and **before** `loadUrl`.

- [ ] **Step 5: Verify**

```bash
python3 /tmp/opencode/balance.py \
  app/src/main/java/app/multisession/browser/projects/editor/EditorActivity.kt
python3 - <<'EOF'
import xml.etree.ElementTree as ET
for f in ["app/src/main/AndroidManifest.xml", "app/src/main/res/values/strings.xml",
          "app/src/main/res/layout/activity_editor.xml", "app/src/main/res/layout/item_editor_row.xml",
          "app/src/main/res/menu/menu_editor.xml"]:
    ET.parse(f)
print("XML OK")
EOF
grep -n "EXTRA_PROJECT_ID\|EXTRA_FILE" app/src/main/java/app/multisession/browser/projects/editor/EditorActivity.kt
grep -c "editor_" app/src/main/res/values/strings.xml   # expect >= 10 (4 Task-2 + 6 Task-3 strings)
grep -n "EditorActivity" app/src/main/AndroidManifest.xml
```
Expected: balance `OK` / `XML OK` / both extras present / manifest line present. Anti-regression: `grep -n "placeholder removed\|webViewClient.let { }\|loadWhenReady\|__ready" app/src/main/java/app/multisession/browser/projects/editor/EditorActivity.kt` → **no matches**.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/multisession/browser/projects/editor/EditorActivity.kt \
        app/src/main/res/layout/activity_editor.xml app/src/main/res/layout/item_editor_row.xml \
        app/src/main/res/menu/menu_editor.xml app/src/main/AndroidManifest.xml \
        app/src/main/res/values/strings.xml
git commit -m "feat: in-app project editor screen (Plan 3)"
```

---

### Task 4: Entry points (Projects screen, browser menu, URL delivery)

**Files:**
- Modify: `app/src/main/java/app/multisession/browser/projects/ProjectManager.kt` (add `locate` after `get(id)`, ~line 40)
- Modify: `app/src/main/java/app/multisession/browser/ui/library/ProjectsActivity.kt` (`showProjectMenu` :64-87, `newFromHtml` :118-123, import)
- Modify: `app/src/main/java/app/multisession/browser/ui/browser/BrowserActivity.kt` (import, `renderMenu` after `local_projects` entry :1142, `handleIntent` :272-281)

**Interfaces:**
- Consumes (exact): `EditorActivity.EXTRA_PROJECT_ID`, `EditorActivity.EXTRA_FILE`, `SimpleListActivity.EXTRA_OPEN_URL` / `EXTRA_IN_NEW_TAB`, `BrowserActivity.openUrl(url, newTab)` (already an override at :818), `R.string.edit`, `R.string.edit_project` (add this string here).
- Produces:
  - `ProjectManager.Located(project: Project, relPath: String)` + `fun locate(url: String): Located?`
  - `ProjectsActivity`: long-press menu item 0 = Edit (launches editor); `newFromHtml` success → editor instead of `returnUrl`
  - `BrowserActivity.handleIntent` opens any Intent carrying `EXTRA_OPEN_URL`
  - Strings: `edit_project`

- [ ] **Step 1: Add the `edit_project` string**

Before `</resources>`: `<string name="edit_project">Edit project</string>`

- [ ] **Step 2: Add `locate` to `ProjectManager`**

Insert after the `fun get(id: String): Project? = ...` line:

```kotlin
    /** The project whose folder contains [url], plus the file path relative to that folder ("" = the folder itself). */
    data class Located(val project: Project, val relPath: String)

    fun locate(url: String): Located? {
        if (!url.startsWith("file://")) return null
        val path = Uri.parse(url).path ?: return null
        return try {
            // Canonicalize BOTH sides like LocalContentLoader.isAllowedLocalUri: raw-vs-canonical
            // prefix tests go dead on devices where filesDir is symlinked (/data/user/0 -> /data/data).
            val prefix = root.canonicalPath + File.separator
            val cpath = File(path).canonicalPath
            if (!cpath.startsWith(prefix)) return null
            val rest = cpath.removePrefix(prefix)
            val id = rest.substringBefore('/')
            if (id.isEmpty()) return null
            val p = get(id) ?: return null
            Located(p, rest.removePrefix(id).removePrefix("/"))
        } catch (t: Throwable) {
            null
        }
    }
```

- [ ] **Step 3: `ProjectsActivity` — Edit action + new-project routes to editor**

Add import: `import app.multisession.browser.projects.editor.EditorActivity`

In `showProjectMenu`, replace the `actions` array and the `when` branches (Edit becomes index 0):

```kotlin
        val actions = arrayOf(getString(R.string.edit), getString(R.string.open_in_new_tab), getString(R.string.export_zip), getString(R.string.delete))
        MaterialAlertDialogBuilder(this).setTitle(p.name).setItems(actions) { _, which ->
            when (which) {
                0 -> startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_PROJECT_ID, p.id))
                1 -> returnUrl(p.url, newTab = true)
                2 -> lifecycleScope.launch {
                    try {
                        val file = core.projects.exportZip(p)
                        val uri = FileProvider.getUriForFile(this@ProjectsActivity, "$packageName.fileprovider", file)
                        val share = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        startActivity(Intent.createChooser(share, getString(R.string.export_zip)))
                        Snackbar.make(recycler, getString(R.string.exported_size, Formatter.formatFileSize(this@ProjectsActivity, file.length())), Snackbar.LENGTH_LONG).show()
                    } catch (t: Throwable) {
                        AppLog.e("Projects", "export failed", t)
                        Snackbar.make(recycler, R.string.export_failed, Snackbar.LENGTH_LONG).show()
                    }
                }
                3 -> MaterialAlertDialogBuilder(this).setTitle(R.string.delete).setMessage(getString(R.string.delete_project_confirm, p.name))
                    .setPositiveButton(R.string.delete) { _, _ -> lifecycleScope.launch { core.projects.delete(p); refresh() } }
                    .setNegativeButton(android.R.string.cancel, null).show()
            }
        }.show()
```

In `newFromHtml`, replace the two lines inside the success block:

```kotlin
                    val p = core.projects.createFromHtml(name.text.toString(), html.text.toString())
                    refresh()
                    startActivity(
                        Intent(this, EditorActivity::class.java)
                            .putExtra(EditorActivity.EXTRA_PROJECT_ID, p.id)
                            .putExtra(EditorActivity.EXTRA_FILE, "index.html")
                    )
```
(The spec records this as the one deliberate behavior change: create now lands in the editor; Preview gives the old result.)

- [ ] **Step 4: `BrowserActivity` — "Edit project" menu entry + intent-based URL open**

Add import: `import app.multisession.browser.projects.editor.EditorActivity` (alphabetically among the `app.multisession.browser.*` imports).

In `renderMenu()`, immediately after the line `entry(R.drawable.ic_folder, R.string.local_projects) { openUrlLauncher.launch(Intent(this, ProjectsActivity::class.java)) }`:

```kotlin
        // v2.1.10 (Plan 3): edit the project that owns the current file:// page.
        tab?.url?.let { u -> core.projects.locate(u)?.let { loc ->
            entry(R.drawable.ic_code, R.string.edit_project) {
                startActivity(
                    Intent(this, EditorActivity::class.java)
                        .putExtra(EditorActivity.EXTRA_PROJECT_ID, loc.project.id)
                        .putExtra(EditorActivity.EXTRA_FILE, loc.relPath.ifBlank { loc.project.entry })
                )
            }
        } }
```

In `handleIntent`, after the notification line `intent.getStringExtra(WebNotifications.EXTRA_TAG)?.let { ... }` and before `val data = intent.data ?: return`:

```kotlin
        // Library/editor screens deliver a URL to open (the SimpleListActivity result contract,
        // also sent by intent in v2.1.10 Plan 3 so the editor preview stays on the back stack).
        intent.getStringExtra(SimpleListActivity.EXTRA_OPEN_URL)?.let { url ->
            openUrl(url, intent.getBooleanExtra(SimpleListActivity.EXTRA_IN_NEW_TAB, false))
            return
        }
```

- [ ] **Step 5: Verify**

```bash
python3 /tmp/opencode/balance.py \
  app/src/main/java/app/multisession/browser/projects/ProjectManager.kt \
  app/src/main/java/app/multisession/browser/ui/library/ProjectsActivity.kt \
  app/src/main/java/app/multisession/browser/ui/browser/BrowserActivity.kt
python3 -c "import xml.etree.ElementTree as ET; ET.parse('app/src/main/res/values/strings.xml'); print('XML OK')"
grep -n "fun locate" app/src/main/java/app/multisession/browser/projects/ProjectManager.kt
grep -n "edit_project" app/src/main/java/app/multisession/browser/ui/browser/BrowserActivity.kt app/src/main/res/values/strings.xml
grep -n "EXTRA_OPEN_URL" app/src/main/java/app/multisession/browser/ui/browser/BrowserActivity.kt   # :215 result + new handleIntent hit
grep -n "R.string.edit)" app/src/main/java/app/multisession/browser/ui/library/ProjectsActivity.kt  # arrayOf(getString(R.string.edit), ...)
```
Expected: all `OK` / `XML OK` / one hit each; `BrowserActivity` `EXTRA_OPEN_URL` now appears twice.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/app/multisession/browser/projects/ProjectManager.kt \
        app/src/main/java/app/multisession/browser/ui/library/ProjectsActivity.kt \
        app/src/main/java/app/multisession/browser/ui/browser/BrowserActivity.kt \
        app/src/main/res/values/strings.xml
git commit -m "feat: editor entry points from the projects screen and app menu (Plan 3)"
```

---

### Task 5: Final verification, push, CI gate

**Files:** none new; whole-plan checks.

- [ ] **Step 1: Full local verification**

```bash
python3 /tmp/opencode/balance.py \
  app/src/main/java/app/multisession/browser/projects/editor/EditorBridge.kt \
  app/src/main/java/app/multisession/browser/projects/editor/EditorActivity.kt \
  app/src/main/java/app/multisession/browser/projects/ProjectManager.kt \
  app/src/main/java/app/multisession/browser/ui/library/ProjectsActivity.kt \
  app/src/main/java/app/multisession/browser/ui/browser/BrowserActivity.kt
python3 - <<'EOF'
import xml.etree.ElementTree as ET
for f in ["app/src/main/AndroidManifest.xml", "app/src/main/res/values/strings.xml",
          "app/src/main/res/layout/activity_editor.xml", "app/src/main/res/layout/item_editor_row.xml",
          "app/src/main/res/menu/menu_editor.xml"]:
    ET.parse(f)
print("XML OK")
EOF
find app/src/main/assets/editor -name '*.js' -exec node --check {} \;
grep -rn "placeholder removed\|TODO\|TBD" app/src/main/java/app/multisession/browser/projects/editor/ || echo "no placeholders"
git status --short   # must show only intended files (CHECKLIST_RESULT.MD / TEST_CHECKLIST_219.md stay untracked)
```
Expected: every balance line `OK`, `XML OK`, node silent, `no placeholders`.

- [ ] **Step 2: Spec-coverage spot checks**

```bash
grep -c "flushSave" app/src/main/assets/editor/editor.html      # >= 4 (debounce + loadFile + 2 window exports)
grep -n "800" app/src/main/assets/editor/editor.html            # debounce constant
grep -n "2 \* 1024 \* 1024" app/src/main/java/app/multisession/browser/projects/editor/EditorBridge.kt
grep -n "project.json" app/src/main/java/app/multisession/browser/projects/editor/EditorBridge.kt
grep -n "findPersistent" app/src/main/assets/editor/editor.html # find + Ctrl-F
```
Expected: all present.

- [ ] **Step 3: Push and watch CI**

```bash
git push origin main
```
Then poll (token from `.git/config`):
`curl -s -H "Authorization: token $TOKEN" "https://api.github.com/repos/Qmgamerzyt/MultiSessionBrowser/actions/runs?per_page=2"` — poll `status`/`conclusion` until `completed/success`. On `failure`, fetch `.../actions/jobs/$ID/logs`, fix, commit, push again.

- [ ] **Step 4: Report**

Summarize for the user: commit range, CI status, and the device checklist from the spec (§7 of `docs/superpowers/specs/2026-10-04-in-app-html-editor-design.md`): create→edit→autosave→kill→persist; preview shows edits; imported project tree; `project.json` read-only; binary and >2 MB refusals; create/rename/delete; `..` rejected; find/line numbers/highlighting; both entry points; import/export/delete regressions.
