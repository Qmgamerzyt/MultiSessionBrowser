# MultiSession Browser (Android 9+)

A real multi-session Android browser / HTML-to-APK runtime powered by **Mozilla GeckoView** (the Firefox engine),
version **155.0.20260903215306** (v2.0.0 – migrated from the system WebView).
Every **session** is an isolated browsing identity (own cookies, logins, localStorage, IndexedDB, service workers,
site permissions) implemented with GeckoView **session contexts** (`contextId`); every session holds any number of
**tabs** that share that identity. The engine is downloaded automatically from `maven.mozilla.org` by Gradle.
APKs are built per ABI (`arm64-v8a`, `armeabi-v7a`, ~90 MB each). See `docs/GECKOVIEW_MIGRATION.md` for the
engine version location and how to update it.

## Build the APK with GitHub Actions (no local tools needed)

1. Create a new GitHub repository and upload the contents of this folder (keep the folder structure,
   including the hidden `.github/` directory).
2. Open the **Actions** tab → **Build APK** → **Run workflow** (it also runs automatically on every push to
   `main`/`master`).
3. When the run finishes, download the artifact **MultiSessionBrowser-debug** (or `-release`) and install
   the APK on your phone (enable "install unknown apps").
4. Optional – tag a release (`git tag v1.0.0 && git push --tags`) and the APKs are attached to a GitHub Release.

### Signing the release APK with your own key (optional)
Without secrets the release APK is signed with the debug key (installable, but not for Play Store).
To use your own key add these repository secrets: `KEYSTORE_BASE64` (`base64 -w0 my.jks`),
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Build locally
Requirements: JDK 17, Android SDK (platform 37, build-tools 36.0.0), Gradle 9.4.1 (or a current Android Studio).
```
gradle wrapper --gradle-version 9.4.1   # once, generates ./gradlew
./gradlew assembleDebug
```
APKs: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` and `app-armeabi-v7a-debug.apk`.

## Toolchain (versions verified together)
| Component | Version |
|---|---|
| Android Gradle Plugin | 9.2.1 (built-in Kotlin, compiler = Kotlin Gradle plugin 2.4.20 via buildscript classpath) |
| Gradle | 9.4.1 |
| KSP / Room | 2.3.12 / 2.8.5 |
| GeckoView | 155.0.20260903215306 (`app/build.gradle.kts` → `geckoViewVersion`) |
| JDK | 17 |
| compileSdk / targetSdk / minSdk | 37.1 (`compileSdk = 37`, `compileSdkMinor = 1`) / 35 / 28 (Android 9) |

## Features
- Sessions: create, rename, colour, private sessions, duplicate (tabs only – never cookies), reset data, delete.
- Tabs per session: Chrome-like grid, thumbnails, swipe to close, drag to reorder, reopen closed tab.
- Real Firefox engine (GeckoView): JavaScript, DOM/IndexedDB storage, WebSockets, popups (`window.open` / `target=_blank`
  → new tab in the same session), file upload incl. camera capture, downloads with per-session cookies,
  camera/microphone (WebRTC) & location prompts, fullscreen video, HTTP auth, SSL warnings.
- Persistence: Room/SQLite (WAL) – sessions, tabs, per-session history, global + session bookmarks; the
  active session & tab are restored after process death.
- Memory: only N most recent tabs keep a live WebView (default 6, configurable); other tabs are frozen with
  `saveState()` and restored on demand; `onTrimMemory` frees more.
- Local HTML projects: import ZIP / HTML / paste HTML, served via `WebViewAssetLoader` over
  `https://appassets.androidplatform.net` (no `file://`). Export as ZIP.
- HTML-to-APK: put your site into `app/src/main/assets/www/` (with `index.html`) → the built APK opens it as
  home page. See `docs/ARCHITECTURE.md` §8.
- Settings: search engine, homepage, JS, zoom, autoplay, third-party cookies, Safe Browsing, user agent
  (default / Chrome-compatible / custom), desktop mode per tab, theme, tabs kept alive, downloads prompt.

## Important honesty notes
- **Isolation requires a modern Android System WebView** (Profile API, `WebViewFeature.MULTI_PROFILE`). On
  Android 9 the WebView is updated through Google Play, so this is normally available. If it is not, the app
  shows a permanent warning banner and all sessions share one cookie jar – it never pretends otherwise.
- The app does not (and cannot) compile APKs on the phone. The project export + this repository + GitHub
  Actions **is** the build workflow.
- Web Notifications are not supported by Android WebView; sites requesting them get `denied`.
- `blob:` downloads cannot be handled by Android DownloadManager; the app tells you and offers an external browser.

See `docs/ARCHITECTURE.md`, `docs/TEST_PLAN.md` and the original specification in `docs/SPEC.md`.

## v2.0.1 – loading fixes & persistent site permissions

**Loading / stuck pages – root causes fixed**
- `BrowserApp.onCreate` ran unguarded in GeckoView's child processes (`:tab0`, `:gpu`, …): every content process opened the Room DB, ran `SessionManager.initialize()` (deleting private sessions, rewriting the active-session preference) and delayed its own start-up. It now returns early outside the main process.
- The `GeckoRuntime` is warmed up in `Application.onCreate` instead of on the first page load.
- Engine settings are re-applied only when a preference actually changed (`BrowserCore.settingsDirty`), not on every return to the foreground.
- `SessionState` JSON serialisation/parsing (tab persistence) moved off the main thread (`TabSnapshot`); URL-bar text is updated only when it changed; thumbnails are scaled off the main thread; loading tabs are the last to be frozen by the live-tab limit.

**Persistent site permissions** (`permissions/SitePermissions.kt`, DB v3 `site_permissions`, migration 2→3 is additive)
- Camera / microphone (getUserMedia) decisions are remembered per site *and* browser session; Gecko never persists these itself.
- Content permissions (location, notifications, autoplay, DRM, …) are answered from the store, mirrored from Gecko's own permission manager (`ContentPermission.value`) and kept in sync via `StorageController.setPermission`.
- Android runtime permissions are requested only when a site is allowed to use the device.

**Site permissions manager**: menu → *Site permissions* (or tap the lock/info icon): Allow / Block / Ask per permission, Reset, and a link to Android settings when the app itself lacks a permission.


## v2.0.2 – tabs & groups, session order, app-owned UI, downloads, javascript:, extensions

**Bug fixes**
- *Mobile-mode WebRTC audio*: Gecko prefs `media.setsinkid.enabled` (exposes `audiooutput` devices to `enumerateDevices()` / `setSinkId`) and `media.navigator.audio.full_duplex` are set through a GeckoView config file (`GeckoEngine.writeConfigFile`, `GeckoRuntimeSettings.configFilePath`). Microphone selection accepts non-`SOURCE_MICROPHONE` inputs. A per-site **Desktop site** rule (Site permissions → "Desktop site (always)", or the Desktop toggle) is applied *before* the load starts, for sites whose mobile-UA code path disables voice. Desktop mode itself is unchanged.
- *Camera/mic revocation*: `SitePermissionStore.set(BLOCK)` for camera/microphone calls `TabManager.revokeMedia`, which reloads every live page of that origin in that session — the MediaStream tracks die with the document and the next `getUserMedia` is denied by the stored rule. Gecko, the app store and the running tracks stay in sync.

**Tabs & groups (DB v4, additive migration 3→4)**: `tab_groups` table, `tabs.groupId`; create/rename/colour/collapse/open/close/ungroup/delete groups, add/move/remove tabs, new tab in group, drag-reorder (drop under a header = move into that group), empty groups persist. `TabManager.applyOrder` writes order + membership atomically.

**Sessions**: `sessions.isDefault` (the first existing session became default in the migration). Default is always listed first and opened on cold start; the rest is drag-reorderable in the session drawer; "Set as default" in the session menu.

**UI**: left three-line button → session drawer; back/forward/undo/redo next to it (undo/redo = page edit undo/redo; long-press undo = reopen closed tab); central address bar (all focus fixes preserved); new-tab + tabs button (tab/group sheet); right three-line button → app menu drawer (with extension buttons and Site permissions); floating selectable HUD (menu → Show HUD / Customize HUD; draggable; "Hide/show toolbar" item).

**Downloads**: app-owned manager (`downloads/AppDownloadManager.kt`, `downloads` table) with progress, pause, resume (HTTP Range), cancel, retry, delete, open, share, copy link, source page, type classification (archive/APK/PDF/image/video/audio/Office/text). APKs open the system installer only after a confirmation; archives are never extracted. Gecko's session-authenticated stream is still used for the initial download (blob:/data: work); resume/retry re-fetch via `GeckoWebExecutor` in the default cookie jar (sign-in protected files report this and must be restarted from the page).

**javascript: URLs**: `UrlUtils.resolveInput` returns them untouched; `BrowserActivity.navigate` runs them via `TabManager.runScript` (wrapped so the completion value is `undefined` → the page is never replaced); `TabDelegates.onLoadRequest` allows exactly that one app-initiated load and keeps denying page-initiated `javascript:` navigations.

**Extensions**: real `WebExtensionController` wiring — see `docs/EXTENSIONS.md` for what works and the exact GeckoView/Android limitations.
