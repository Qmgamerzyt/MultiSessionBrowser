# GECKOVIEW_CONTINUATION.md — GeckoView migration status

**Run:** 2026-09-12 · **Result:** migration IMPLEMENTED (code complete, statically validated).
**Not yet done:** an actual Gradle build (no Android SDK / Gradle in this environment) and device tests.

## Completed work
- Toolchain upgraded to the prep-run "Mozilla-aligned" set: Gradle 9.4.1, AGP 9.2.1 (built-in Kotlin;
  `org.jetbrains.kotlin.android` plugin removed), KSP 2.3.12, Room 2.8.5, compileSdk 37, targetSdk 35,
  minSdk 28, JDK 17, androidx (core 1.19.0, appcompat 1.7.1, activity 1.13.0, fragment 1.8.9,
  recyclerview 1.4.0, lifecycle 2.11.0, material 1.14.0, preference 1.2.1), coroutines 1.11.0.
- GeckoView `org.mozilla.geckoview:geckoview:155.0.20260903215306` from `https://maven.mozilla.org/maven2/`
  (single definition: `val geckoViewVersion` in `app/build.gradle.kts`). ABI splits arm64-v8a + armeabi-v7a,
  no universal APK; per-ABI versionCode via `androidComponents.onVariants`.
- Engine swap: all `android.webkit.WebView` / `androidx.webkit` code removed (package `webview/` deleted,
  dependency removed). New package `engine/`: `GeckoEngine` (single GeckoRuntime, lazy, global prefs,
  StorageController), `SessionFactory` (GeckoSessionSettings: **contextId per browser session**, private mode,
  UA/viewport), `TabDelegates` (Navigation/Progress/Content/Permission delegates), `BrowserPromptDelegate`
  (alert/confirm/prompt, `<select>`, date/time, colour, HTTP auth, file upload, popup block, beforeunload,
  repost, share), `DownloadHandler` (onExternalResponse → WebResponse stream → Downloads, blob: works),
  `LocalContentLoader` (resource://android/assets/www/ + sandboxed file:// for projects), `BrowserHost`.
- Session isolation: `SessionIsolation` → `SessionEntity.contextId` ("session-<uuid>"), set in the settings
  builder before `open()`; reset/delete → `clearDataForSessionContext`. Always isolated (banner logic kept but
  never shown).
- Tabs: `TabManager` keeps one open GeckoSession per live tab, `setActive/setFocused` for the displayed one,
  hibernate = keep `SessionState` + `close()`, re-open = `restoreState`. Popups via `onNewSession` →
  `createPopupTab` (same contextId), shown from `onPageStart`. Crash/kill handling via `onCrash/onKill`.
- Persistence: Room DB **v2** — `tabs.sessionState TEXT` added with `Migration(1,2)` (no data reset);
  `fallbackToDestructiveMigrationOnDowngrade(true)` only for downgrades.
- BrowserActivity: ONE `GeckoView`, attached only with an open session, sessions swapped on tab switch;
  v1.1.2 focus fix preserved (focusHolder, `onStart` does not re-attach/reload the displayed tab,
  `onFocusRequest` ignored, touch listener exits search mode). Displayed session stays active in background
  (Discord voice keeps running). Fullscreen video via `onFullScreen` (chrome hidden, `exitFullScreen` on Back).
  Permissions: Android runtime perms (`onAndroidPermissionsRequest`), getUserMedia dialog choosing
  camera/mic (`onMediaPermissionRequest`), content permissions (geolocation prompt, autoplay by pref,
  DRM prompt, storage, notifications denied). Downloads with ask-dialog, storage permission on Android 9.
  Context menu from `onContextMenu`. Thumbnails via `GeckoView.capturePixels()`.
- Version bumped 1.1.2 (code 4) → **2.0.0 (code 5**, ×10+ABI in split outputs).
- Workflow: `platforms;android-37`, `build-tools;36.0.0`, Gradle 9.4.1, timeout 60 min, APK globs unchanged.
- Docs: `docs/GECKOVIEW_MIGRATION.md` (version location, update procedure, behaviour differences), README.

## Files changed / added / deleted
Changed: `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties`,
`.github/workflows/build-apk.yml`, `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`,
`BrowserApp.kt`, `core/BrowserCore.kt`, `core/UrlUtils.kt`, `core/Prefs.kt` (comment), `data/db/Entities.kt`,
`data/db/AppDatabase.kt`, `projects/ProjectManager.kt`, `session/SessionIsolation.kt`, `session/SessionManager.kt`,
`tabs/Tab.kt`, `tabs/TabManager.kt`, `ui/browser/BrowserActivity.kt`, `ui/library/ProjectsActivity.kt` (comment),
`res/values/strings.xml`, `res/values/arrays.xml`, `README.md`.
Added: `engine/GeckoEngine.kt`, `engine/SessionFactory.kt`, `engine/TabDelegates.kt`, `engine/BrowserPromptDelegate.kt`,
`engine/DownloadHandler.kt`, `engine/LocalContentLoader.kt`, `engine/BrowserHost.kt`, `docs/GECKOVIEW_MIGRATION.md`.
Deleted: `webview/` (WebViewFactory, BrowserWebViewClient, BrowserChromeClient, DownloadHandler, LocalContentLoader, BrowserHost).
Untouched: DAOs, repository, sessions UI, tabs sheet, settings UI, suggestions, start page, layouts, drawables.

## Current errors
None known. The project could NOT be compiled here (no Gradle/Android SDK in the sandbox) — validation
was static: bracket balance of all 34 Kotlin files, no leftover WebView API references, resource names
cross-checked, GeckoView API signatures written from the public Javadoc (see risk list).

## Exact next actions (next run / first CI run)
1. Push → run **Build APK**. Fix whatever the compiler reports; the most likely spots are listed below.
2. Device tests on an Android 9 arm64 phone: (a) log in to the same site as user A in session 1 and user B
   in session 2, switch back and forth — each keeps its own login (isolation); (b) OTP flow: focus a web
   input → home → copy → return → paste lands in the web input, no keyboard on the URL bar; (c) Discord
   voice call (mic prompt → Android permission → audio in/out), minimise/restore keeps the call;
   (d) file upload, download (incl. blob), `<select>`, alert/confirm, HTTP auth; (e) tab switching keeps
   scroll/form state; kill the process → tabs + history restored.
3. Optional follow-ups: WebNotificationDelegate, favicons via WebExtension, R8 shrinking.

## API-risk list (check first if the build fails)
- `NavigationDelegate.onLocationChange(session, url, perms, hasUserGesture: Boolean)` — 4-arg form (added ~v123).
  If 155 renamed/removed it, switch to the variant present in the 155 Javadoc.
- `ContentBlocking.Settings.Builder.antiTracking/safeBrowsing/cookieBehavior`; `GeckoRuntimeSettings.Builder.loginAutofillEnabled/aboutConfigEnabled`.
- `GeckoSessionSettings.Builder.suspendMediaWhenInactive/useTrackingProtection/userAgentOverride`.
- `PromptDelegate` nested prompt classes (`ChoicePrompt.confirm(Choice)`, `FilePrompt.confirm(Context, Uri[])`,
  `DateTimePrompt.Type.*`, `SharePrompt.Result.*`, `AuthPrompt.AuthOptions.Flags.ONLY_PASSWORD`).
- Room 2.8.5: `fallbackToDestructiveMigrationOnDowngrade(Boolean)`, `androidx.room.withTransaction` in room-runtime.
- AGP 9 DSL: `splits.abi`, `androidComponents.onVariants { output.versionCode }`, `packaging.jniLibs.useLegacyPackaging`.
- Kotlin nullability warnings on Java-annotated overrides are warnings, not errors.

## Dependency / toolchain versions (final)
Gradle 9.4.1 · AGP 9.2.1 · KSP 2.3.12 · Room 2.8.5 · GeckoView 155.0.20260903215306 · compileSdk 37 ·
targetSdk 35 · minSdk 28 · JDK 17 · core-ktx 1.19.0 · appcompat 1.7.1 · activity-ktx 1.13.0 ·
fragment-ktx 1.8.9 · recyclerview 1.4.0 · preference-ktx 1.2.1 · lifecycle-runtime-ktx 2.11.0 ·
material 1.14.0 · kotlinx-coroutines-android 1.11.0.

## Unresolved / accepted limitations
- contextId is also set for private sessions (private mode + contextId). If GeckoView 155 rejects that
  combination at `open()`, drop `.contextId()` for `session.isPrivate` in `SessionFactory` (private data is
  memory-only anyway).
- file:// loading of imported projects relies on Gecko's file protocol handler (parent-process I/O);
  verify on device — fallback would be `GeckoSession.Loader().data(bytes, "text/html")` for the entry file.
- No cert-error override, no favicons, notifications denied, screen share rejected, "download image"
  fetch has no session cookies (see docs/GECKOVIEW_MIGRATION.md).
