# MultiSession Browser (Android 9+)

A real multi-session Android WebView browser / HTML-to-APK runtime.
Every **session** is an isolated browsing identity (own cookies, logins, localStorage, IndexedDB, cache,
service workers) implemented with the AndroidX WebKit **Profile API**; every session holds any number of
**tabs** that share that identity. Built with the system Android WebView (0 MB engine overhead – no GeckoView).

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
Requirements: JDK 17, Android SDK (platform 35), Gradle 8.9 (or Android Studio Ladybug+).
```
gradle wrapper --gradle-version 8.9   # once, generates ./gradlew
./gradlew assembleDebug
```
APK: `app/build/outputs/apk/debug/app-debug.apk`.

## Toolchain (versions verified together)
| Component | Version |
|---|---|
| Android Gradle Plugin | 8.7.3 |
| Gradle | 8.9 |
| Kotlin / KSP | 2.0.21 / 2.0.21-1.0.28 |
| JDK | 17 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 28 (Android 9) |
| androidx.webkit (Profile API) | 1.14.0 |
| Room | 2.6.1 |

## Features
- Sessions: create, rename, colour, private sessions, duplicate (tabs only – never cookies), reset data, delete.
- Tabs per session: Chrome-like grid, thumbnails, swipe to close, drag to reorder, reopen closed tab.
- Real WebView: JavaScript, DOM/IndexedDB storage, WebSockets, popups (`window.open` / `target=_blank`
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
