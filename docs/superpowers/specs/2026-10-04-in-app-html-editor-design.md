# Design: In-app HTML project editor (v2.1.10, Plan 3)

Date: 2026-10-04 · Status: approved (brainstorming session, 2026-10-04)
Related: `PLAN2_2110_SCALE_PERF.md`, `BUGFIX_2110_A9.md`

## 1. Problem & goal

Projects (`filesDir/projects/<id>/`, `project.json {name, entry}`, served to
Gecko as `file://` via `LocalContentLoader`) can be imported, exported,
previewed and deleted — but never edited on-device. Editing today requires
pulling files off the device and using a desktop editor.

**Goal:** a full in-app editor: multi-file editing with a file tree, line
numbers, syntax highlighting, in-editor find, autosave, and file
create/rename/delete, with preview opening in a normal browser tab.

Decisions taken during brainstorming (Q1–Q5):

| # | Question | Decision |
|---|---|---|
| Q1 | Core scope | (c) multi-file + line numbers, syntax highlighting, find, autosave, new/rename/delete |
| Q2 | Highlighting mechanism | (a) CodeMirror 5 bundled in `assets/` |
| Q3 | Preview | (a) "Preview in tab" action, no split view (YAGNI; kept addable later) |
| Q4 | Entry points | (c) both — Projects screen **Edit** action and browser overflow "Edit project" while viewing a project URL |
| Q5 | Editor host | (a) system `android.webkit.WebView` + `addJavascriptInterface` (simple bridge, outside the patched Gecko stack) |

## 2. Scope

**In scope**
- Edit any text file of a project, with CodeMirror (modes: XML, JavaScript,
  CSS, HTML-mixed; search addon; one dark theme).
- File tree: browse, open, create, rename, delete; `project.json` listed but
  read-only.
- Autosave (800 ms debounce + immediate flush on Preview / back / file switch).
- Preview in a normal browser tab (existing `project.url` open path).
- Both entry points (Q4).

**Out of scope (explicit)**
- Split-view live preview (Q3 — the preview host stays independent so this can
  be added later without rework).
- Project rename / create / delete (remain in `ProjectsActivity`).
- Binary or asset editing (images etc. are listed but refused with a message).
- Git integration, editor preferences/settings, remote files.

## 3. Architecture

New package `app.multisession.browser.projects.editor`:

```
EditorActivity ── loads ──► WebView (system) ── loads ──► assets/editor/editor.html
     │                                                   (+ CodeMirror 5 dist)
     │  @JavascriptInterface: EditorBridge
     ├─ list()            → tree JSON (relative paths, sizes, text/binary flag)
     ├─ open(path)        → file content (UTF-8) or binary/size error marker
     ├─ save(path, content) → atomic write (tmp + rename) on Dispatchers.IO
     ├─ create(path)      → empty file (parent must exist)
     ├─ rename(from, to)
     └─ delete(path)
```

- **Assets:** `app/src/main/assets/editor/` contains `editor.html`,
  `codemirror.min.js/css`, mode files (`xml`, `javascript`, `css`,
  `htmlmixed`), addon files (`search`, `dialog` + `dialog.css`), and one dark
  theme. Upstream: CodeMirror 5 (MIT). Fetched once and committed to the repo;
  the APK build has no network dependency.
- **Loading:** `WebViewAssetLoader` with the standard
  `https://appassets.androidplatform.net/editor/` origin — gives the page a
  trustworthy origin for `addJavascriptInterface` (interface is injected only
  for that host) and avoids `file://` access flags entirely.
- **Bridge security:** every path argument is normalized then checked with a
  canonical-path-inside-`project.dir` rule (same policy as
  `LocalContentLoader.isAllowedLocalUri`); `..`, symlinks and absolute paths
  are rejected. `project.json` operations are rejected server-side in the
  bridge regardless of UI.
- **UI shell:** `EditorActivity` follows `ProjectsActivity`'s style —
  activity + suspend calls, no ViewModel/DI. Toolbar: up-navigate, project
  name + file path, dirty indicator, **Preview**, overflow (New file, Rename,
  Delete, Find). Left drawer: file tree (RecyclerView, matching the app's
  existing list patterns). Editor: WebView filling the remaining space,
  `adjustResize` for the IME.

## 4. Data flow & save model

1. File selected → `bridge.open(path)` → content into CodeMirror; mode by
   extension: `html/htm → htmlmixed`, `js/mjs → javascript`, `css → css`,
   `xml/svg → xml`, `json → javascript`, `md/txt → plain` (gutter + line
   numbers on for all).
2. Keystroke → CodeMirror `change` event → JS debounce 800 ms →
   `bridge.save(path, content)` → IO write → dirty indicator clears.
3. **Flush points** (bypass the debounce): Preview tap, back-press (with
   pending state), switching files in the drawer. Worst-case data loss:
   the last 800 ms, only on crash.
4. Save writes `content + ".tmp"` then renames (atomic within the project
   directory), so a crash mid-write cannot truncate the original.
5. New file → dialog for name → `bridge.create` → open it.
   Rename/delete → confirm dialog → `bridge.rename`/`delete` → tree refresh.
6. **Preview:** flush save → `startActivity` browser tab at `project.url`
   (identical path to `ProjectsActivity`'s open action).

**File policy:** editable = extension allowlist `html, htm, css, js, mjs,
json, txt, md, svg, xml` **and** UTF-8-decodable content; anything else is
listed as binary and refused with a toast. Files > 2 MB are refused with a
toast (whole-file string crosses the bridge).

## 5. Entry points

1. **`ProjectsActivity`** — per-row **Edit** action → `EditorActivity(id)`
   (opens `entry` file). **New project** (`newFromHtml`, existing dialog):
   after `createFromHtml` land in the editor instead of today's
   create→open-tab (one deliberate behavior change; Preview restores the old
   result).
2. **`BrowserActivity`** overflow menu — item **"Edit project"**, added
   dynamically; visible only when the current tab's URL is inside a project
   directory (projects-root prefix match, id extracted from the path). Opens
   the relative path of the current page; if that file no longer exists, falls
   back to the project's `entry` file.

## 6. Error handling

Existing app conventions only: suspend calls return values or throw; the
activity converts failures to `AppLog` + a short toast (same shape as
import/export in `ProjectsActivity`). **No new try/catch layers** beyond
mirroring existing same-path patterns. Bridge calls from JS report errors back
as structured `{ok:false, error}` objects so the WebView never hangs on a
failing call.

## 7. Testing / verification

Local (no compile available): brace-balance diff vs HEAD for every touched
Kotlin file, `ET.parse` on touched XML, greps for the removed/added symbols,
`node --check` unused here (no omni patch in this plan).
Gate: **CI workflow (push)** — the only compile check.

Manual checklist (device):
- [ ] New project → editor opens `index.html`, edit, autosave, kill app,
      reopen → changes persisted.
- [ ] Preview tab shows the edited content after flush.
- [ ] Existing imported project: all files listed; `project.json` not editable;
      binary file refused; >2 MB file refused.
- [ ] Create / rename / delete file; parent dirs respected; `..` rejected.
- [ ] Find (in-editor), line numbers, highlighting for html/css/js.
- [ ] Both entry points; browser-menu item hidden on non-project pages.
- [ ] Regression: import/export/delete from `ProjectsActivity` unchanged.

## 8. Risks

- **Second renderer process** (system WebView alongside Gecko) — acceptable;
  WebView ships with the OS, cost is one process only while editing.
- **Bridge string size** on huge files — mitigated by the 2 MB cap.
- **IME/focus quirks** between drawer and WebView — standard
  `adjustResize` handling; verified in the manual checklist.
