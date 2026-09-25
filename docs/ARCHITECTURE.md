# Architecture

## A. Platform capability audit
The original brief mentions "HopWeb". No such build platform could be identified or inspected, so the
project was built as a **standard native Android project (Kotlin, Gradle, Android Studio-compatible)** whose
APK is produced by **GitHub Actions**. This gives full access to every capability the brief needs:

| Need | Available? | How |
|---|---|---|
| Real Android WebView | Yes | `android.webkit.WebView` (system WebView, Play-updated) |
| Isolated cookies/storage per session | Yes* | AndroidX WebKit **Profile API** (`ProfileStore`, `WebViewCompat.setProfile`) |
| Native permissions (camera, mic, location, storage) | Yes | Runtime permissions + `WebChromeClient` |
| Room / SQLite persistence | Yes | Room 2.6.1 |
| Dynamic APK compilation on device | **No** | Not possible on Android; solved by repo + CI build |
| Web Notifications inside WebView | **No** | Android WebView has no notification support |

\* Requires `WebViewFeature.MULTI_PROFILE` on the device (Android System WebView ≥ ~Chrome 110 era). Checked at
runtime; fallback = shared profile **with a visible warning**, never silent.

Engine choice: GeckoView (Firefox) adds ~50 MB per APK and its own update cadence; the system WebView adds
0 MB, is updated by Play on Android 9, and is the only engine with the Profile API. System WebView was chosen.

## B. Technical architecture (single process, application-scoped core)
```
BrowserApp (Application)
 └── BrowserCore  (single source of truth, lives for the whole process)
      ├── AppDatabase / BrowserRepository   Room: sessions, tabs, history, bookmarks
      ├── SessionIsolation                  Profile API wrapper (+ honest fallback)
      ├── SessionManager                    StateFlow<sessions>, active session, CRUD, reset, duplicate
      ├── TabManager                        all Tab objects, WebView lifecycle (create/hibernate/destroy), LRU
      ├── LocalContentLoader                WebViewAssetLoader (assets/www + imported projects)
      ├── ProjectManager                    ZIP/HTML import, export, zip-slip protection
      └── DownloadHandler                   DownloadManager with per-session cookies, data: URLs
UI
 ├── BrowserActivity (BrowserHost)         toolbar, WebView container, start page, dialogs, permissions
 ├── TabsSheet / SessionsSheet             bottom sheets (grid / list)
 ├── History / Bookmarks / Projects        SimpleListActivity subclasses
 └── SettingsActivity                      PreferenceFragmentCompat
```
WebViews are created with `MutableContextWrapper(activity)` and owned by `TabManager`, so they survive
Activity recreation (rotation, theme change) without reloading and without leaking the Activity.

## C. Session isolation strategy
| Data | Mechanism |
|---|---|
| Cookies (incl. HttpOnly/Secure/SameSite) | Each session = one WebView `Profile`; `profile.cookieManager` is a separate jar managed by the WebView network stack. Nothing is read/copied in JS. |
| localStorage / IndexedDB / WebSQL / Cache API | Per profile (`profile.webStorage`). |
| HTTP cache, service workers | Per profile (Chromium storage partition). |
| Geolocation grants | Per profile (`profile.geolocationPermissions`). |
| sessionStorage | Per tab by definition (per WebView). |
| Login state | Follows cookies + storage above → per session. Tabs of one session share it (same profile). |
| History, bookmarks, tabs | Room rows keyed by `sessionId`. |
| Reset session | `removeAllCookies`, `WebStorage.deleteAllData`, `clearCache` – on that profile only. |
| Delete session | `ProfileStore.deleteProfile(name)` after destroying its WebViews → all disk data removed. |

Rules enforced in code: the profile is attached **before** any load (`WebViewFactory.create`), popup windows
created via `onCreateWindow` get a WebView bound to the *same* profile, downloads carry the *session's* cookies.

Facts that shaped this: multiple `WebView` objects share one default profile; `setDataDirectorySuffix()` is
per-process, not per-WebView; JavaScript-based cookie tricks cannot see HttpOnly cookies. Hence the Profile API.

## D. Project structure
```
.github/workflows/build-apk.yml       CI: debug + release APK artifacts, GitHub Release on tags
app/build.gradle.kts                  AGP 8.7.3, minSdk 28, deps
app/src/main/AndroidManifest.xml
app/src/main/assets/www/              standalone HTML (optional)
app/src/main/java/app/multisession/browser/
  BrowserApp.kt
  core/        AppLog, Prefs, UrlUtils, BrowserCore
  data/        BrowserRepository, db/{Entities, Daos, AppDatabase}
  session/     SessionIsolation, SessionManager
  tabs/        Tab, TabManager
  webview/     BrowserHost, WebViewFactory, BrowserWebViewClient, BrowserChromeClient, DownloadHandler, LocalContentLoader
  projects/    ProjectManager
  ui/browser/  BrowserActivity, StartPageController
  ui/tabs/     TabsSheet     ui/sessions/ SessionsSheet, SessionEditDialog
  ui/library/  SimpleListActivity, HistoryActivity, BookmarksActivity, ProjectsActivity
  ui/settings/ SettingsActivity
app/src/main/res/                     layouts, vector icons, themes (light/dark), strings, preferences
docs/                                 this file, TEST_PLAN.md, SPEC.md
```

## E. Roadmap / status
| Phase | Status |
|---|---|
| 1 Research (WebView profiles, toolchain, CI) | done |
| 2 Foundation (project, Room, models, managers) | done |
| 3 WebView (navigation, JS, cookies, storage, downloads, uploads, permissions) | done |
| 4 Session isolation (Profile API + fallback banner) | done – needs on-device verification (see TEST_PLAN) |
| 5 Tabs (switching, persistence, lifecycle, LRU hibernation) | done |
| 6 UI (toolbar, session switcher, tab grid, settings, start page, error pages) | done |
| 7 Local HTML (asset loader, projects import/export) | done |
| 8 APK/Export (CI workflow, standalone assets/www mode) | done |
| 9 Testing on real devices | **to do by you** – follow docs/TEST_PLAN.md |
| 10 Production cleanup (enable R8, real signing key, icons) | partially – R8 off by default for deterministic first build |

## F. Risks & limitations

> **Superseded by v2.0.0 (GeckoView 155).** The four WebView-era risks below describe the engine this
> document was written for. Isolation no longer depends on the platform WebView at all: every session gets
> its own `GeckoSessionSettings.contextId` (see `SessionFactory` / `SessionIsolation`), so there is no
> `MULTI_PROFILE` fallback, no shared-cookie-jar banner and no `WebView.clearCache()` question anymore.
> Current engine-specific risks live in `docs/GECKOVIEW_MIGRATION.md`.

- Devices whose System WebView lacks `MULTI_PROFILE`: sessions share data (banner shown). Fix = update WebView.
- `WebView.clearCache()` scope with profiles should be verified on device; profile deletion always removes everything.
- Private sessions use a persistent profile while alive; data is removed when the session is deleted or on
  the next app start. This is stated in the UI.
- Some sites (notably Google sign-in) reject the WebView user agent (`; wv`). Settings → User agent →
  "Mobile (Chrome-compatible)" removes the marker; default UA is not spoofed.
- Restoring a frozen tab uses `restoreState()` (history + URL); page DOM/scroll/form state is only preserved
  while a tab's WebView is alive (default: 6 most recent).
- `blob:` downloads and Web Notifications are not supported by Android WebView.
- Multi-process-per-session fallback (`setDataDirectorySuffix`) was deliberately not implemented: it
  requires one Android process per session and would double the memory cost; the Profile API is the
  supported mechanism.

## G. Extension points
`BrowserHost` (UI ↔ WebView), `WebViewFactory.applySettings` (per-site settings, user scripts),
`BrowserWebViewClient.shouldInterceptRequest` (ad blocking, proxies), `SessionEntity` (per-session proxy/VPN
config), Room migrations for sync/backup.

## 8. HTML-to-APK ("standalone") mode
Copy your web project into `app/src/main/assets/www/` so that `index.html` is directly inside `www/`.
`LocalContentLoader.bundledAppUrl()` detects it and `BrowserActivity.homeUrl()` opens it in every new tab.
Change `applicationId`, `versionName` (app/build.gradle.kts) and `app_name` (strings.xml), push, and let
the workflow build your APK.
