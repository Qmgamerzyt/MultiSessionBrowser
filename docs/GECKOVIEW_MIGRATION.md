# GeckoView engine — where the version lives and how to update it

The app runs on **Mozilla GeckoView** (the Firefox engine). The engine is an ordinary Gradle
dependency resolved from Mozilla's Maven repository; nothing is downloaded by hand and there are
no local AAR files.

## Single source of truth for the version

`app/build.gradle.kts`:
```kotlin
val geckoViewVersion = "155.0.20260903215306"
...
implementation("org.mozilla.geckoview:geckoview:$geckoViewVersion")
```
`settings.gradle.kts` adds the repository: `maven { url = uri("https://maven.mozilla.org/maven2/") }`.

## Updating GeckoView

1. Pick a stable version from https://maven.mozilla.org/?prefix=maven2/org/mozilla/geckoview/geckoview/
   (format `<FirefoxMajor>.<minor>.<buildId>`; pin the exact string, never `155.+`).
2. Open its POM (`.../geckoview/<ver>/geckoview-<ver>.pom`) and check the transitive minimums:
   `kotlin-stdlib` (our compiler must be ≥ that major/minor — with AGP built-in Kotlin this means
   the AGP version), `androidx.core` (dictates the minimum `compileSdk`), `lifecycle`, `media3`.
3. Change `geckoViewVersion`; if step 2 demands it, raise `compileSdk`, AGP (`build.gradle.kts`),
   Gradle (`gradle/wrapper/gradle-wrapper.properties` **and** `.github/workflows/build-apk.yml`
   `gradle-version`), and the SDK platform installed by the workflow (`platforms;android-XX`).
4. Push; the **Build APK** workflow must go green. GeckoView API changes surface as Kotlin compile
   errors in `app/src/main/java/app/multisession/browser/engine/`.

## Toolchain that matches GeckoView 155 (verified together)

| Component | Version | Where |
|---|---|---|
| Gradle | 9.4.1 | `gradle/wrapper/gradle-wrapper.properties`, workflow |
| Android Gradle Plugin | 9.2.1 (built-in Kotlin, no `kotlin-android` plugin) | `build.gradle.kts` |
| KSP | 2.3.12 | `build.gradle.kts` |
| Room | 2.8.5 | `app/build.gradle.kts` |
| compileSdk / targetSdk / minSdk | 37 / 35 / 28 | `app/build.gradle.kts` |
| JDK | 17 | workflow |
| GeckoView | 155.0.20260903215306 | `app/build.gradle.kts` |

## Architecture after the migration (engine package `app.multisession.browser.engine`)

| Concern | Class |
|---|---|
| Single `GeckoRuntime` per process, global settings, storage clearing | `GeckoEngine` |
| Per-tab `GeckoSession` creation (contextId, private mode, UA/viewport) | `SessionFactory` |
| Navigation / progress / content / permission callbacks of a tab | `TabDelegates` |
| JS dialogs, `<select>`, date/time, HTTP auth, file upload, popups, beforeunload | `BrowserPromptDelegate` |
| Downloads from `ContentDelegate.onExternalResponse` (`WebResponse` stream → Downloads folder) | `DownloadHandler` |
| Local HTML (bundled `resource://android/assets/www/`, projects via sandboxed `file://`) | `LocalContentLoader` |
| UI contract implemented by `BrowserActivity` | `BrowserHost` |

Session isolation: `session/SessionIsolation` maps every browser session to a Gecko **contextId**
(`SessionEntity.contextId`); all GeckoSessions of a session are created with it before `open()`.
"Reset session" → `StorageController.clearDataForSessionContext(contextId)`.

Tabs: `tabs/TabManager` keeps one open `GeckoSession` per live tab (up to *Live tabs* setting),
stores Gecko `SessionState` (history/scroll/form state) in Room (`tabs.sessionState`, DB v2 with
migration) and closes/re-opens sessions lazily. `BrowserActivity` owns ONE `GeckoView` and only
swaps sessions into it — tab switching never destroys sessions.

## Known behaviour differences vs. the WebView version

- APK size ~90 MB per ABI (engine included) instead of ~3 MB.
- Certificate errors show the native error page; there is no "proceed anyway" (Gecko does not expose
  a cert-override API to embedders).
- Favicons are not provided by GeckoView; the tab grid shows a generic icon.
- Web notifications are denied (would need a `WebNotificationDelegate`); screen sharing is rejected.
- "Download image" from the context menu fetches through Gecko's network stack **without** the
  session's cookies (public images only). Regular downloads (links, blobs) are fully session-aware.
- HTTP cache clearing is global (Gecko has one cache store); cookies/storage clearing is per session.
