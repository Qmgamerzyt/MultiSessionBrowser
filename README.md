# MultiSession Browser

**A private, multi-session Android browser built on Mozilla GeckoView.**
Every *session* is a fully isolated browsing identity — its own cookies, logins, storage and site
permissions — and every session holds any number of tabs that share that identity. Run your work,
personal and incognito lives side by side without signing out of anything.

[![Build APK](https://github.com/Qmgamerzyt/MultiSessionBrowser/actions/workflows/build-apk.yml/badge.svg)](https://github.com/Qmgamerzyt/MultiSessionBrowser/actions/workflows/build-apk.yml)
[![Latest release](https://img.shields.io/github/v/release/Qmgamerzyt/MultiSessionBrowser)](../../releases)

- **Engine:** Mozilla GeckoView 155 (the Firefox engine) — not the system WebView
- **Android:** 9+ (minSdk 28), arm64-v8a & armeabi-v7a APKs
- **Source:** open — build it yourself with GitHub Actions, no local toolchain required

---

## Features

**Sessions**
- Create, rename, colour, reorder and delete sessions; one session is always the *default*
- Incognito sessions (Gecko private mode, never persisted), duplicate-a-session (tabs only — never cookies), per-session reset
- Session drawer with live tab counts; cold start always opens the default session

**Tabs**
- Chrome-like tab grid with thumbnails, swipe-to-close (with undo), drag-to-reorder, pinning, archiving
- Tab groups: create, colour, collapse, drag tabs in/out; empty groups persist
- Recently closed tabs & groups, reopen-closed-tab, live-tab limit with hibernation for memory

**Privacy & isolation**
- Each session gets its own Gecko session context (`contextId`): cookies, localStorage, IndexedDB,
  service workers, cache and permissions are partitioned per session — engine-level, not a wrapper
- Per-site, per-session permission manager (camera, microphone, location, notifications, autoplay, DRM, desktop site)
- No credential autofill, no third-party telemetry; Safe Browsing and tracking protection follow Gecko

**Browsing**
- Full Firefox engine: modern JavaScript/WebAssembly, WebSockets, popups (`window.open` / `target=_blank`), file & camera upload,
  downloads with session cookies, HTTP auth, SSL warnings, fullscreen video, WebRTC audio
- Address-bar suggestions (history, bookmarks, search), desktop mode per tab, custom user agents
- App-owned download manager: pause/resume/cancel/retry, type classification, share/open/copy link
- WebExtension support (install from AMO; see the in-app Extensions screen for current limits)
- Persistent site permissions, pull-to-refresh, per-tab page zoom (native `fullZoom`, CSP-proof)

**Local HTML projects & HTML-to-APK**
- Import ZIP/HTML, paste HTML, edit files **in-app** (CodeMirror editor with syntax highlighting,
  file tree, find, 800 ms autosave, preview-in-tab), export as ZIP
- Package your own site in `app/src/main/assets/www/` and the built APK opens it as its home page

**Persistence**
- Room/SQLite (WAL): sessions, tabs, groups, per-session history, bookmarks, permissions, downloads,
  workspaces, recently-closed — restored after process death or reboot

---

## Install

1. Grab the newest APK from **[Releases](../../releases)** (`…-arm64-v8a-release.apk` for almost every phone,
   `…-armeabi-v7a-release.apk` for older 32-bit devices).
2. Open it and allow "install unknown apps" for your file manager/browser.
3. Update in place any time a newer release ships (same signing key on every release).

Every release page lists a full changelog of what changed.

---

## Build it yourself (no local tools)

1. Fork/push this repository to GitHub (keep the hidden `.github/` directory).
2. Open **Actions → Build APK** — it runs on every push to `main` and can be started manually.
3. When it finishes, download the **MultiSessionBrowser-…-release** (or `-debug`) artifact and install the APK.
4. Optional: tag a version — `git tag v2.2.0-beta-1 && git push --tags` — and the APKs are attached
   to a GitHub Release with the changelog automatically.

The build refuses stale checkouts (`versionCode` gate), requires the pinned release keystore secrets
and verifies the signer fingerprint, and keeps the browsing engine on GeckoView (a CI guard rejects
WebView references outside the in-app editor host).

### Release signing (repository secrets)
`KEYSTORE_BASE64` (`base64 -w0 my.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Without them the release APK falls back to the debug key — fine for testing, but such builds cannot
update over a properly signed release.

### Build locally
JDK 17 + Android SDK (platform 37.1, build-tools 36.0.0):

```
gradle wrapper --gradle-version 9.4.1   # once, generates ./gradlew
./gradlew assembleDebug
```

### Toolchain (versions verified together)

| Component | Version |
|---|---|
| Android Gradle Plugin | 9.2.1 (built-in Kotlin; Kotlin Gradle plugin 2.4.20 via buildscript classpath) |
| Gradle | 9.4.1 |
| KSP / Room | 2.3.12 / 2.8.5 |
| GeckoView | 155.0.20260903215306 (`geckoViewVersion` in `app/build.gradle.kts`) |
| JDK | 17 |
| compileSdk / targetSdk / minSdk | 37.1 / 35 / 28 (Android 9) |

---

## Repository layout

```
app/src/main/java/app/multisession/browser/
  core/        BrowserCore, AppLog, Prefs — process-wide state
  data/        Room database (entities, DAOs, repository)
  tabs/        TabManager, Tab model, restore/hibernation
  session/     SessionManager, per-session Gecko isolation
  engine/      GeckoEngine, session factories, delegates
  ui/          browser, sessions, tabs, library, settings, projects
  projects/    local HTML projects + in-app editor
  extensions/  WebExtension wiring
  downloads/   app-owned download manager
  permissions/ per-site permission store
.github/workflows/   CI: build, sign, gate, publish
tools/               omni.ja patch script used by CI
```

## Contributing

Issues and pull requests are welcome.

- Describe bugs with your device, Android version, app version and exact steps; a `logcat`
  snippet with tag `MSB.*` is gold.
- Keep the project conventions: version-tagged KDoc for user-visible changes, additive-only Room
  migrations, no new `try/catch` that swallows failures silently, CI is the compile gate.
- Feature work starts from a short design spec; releases are beta-train until a feature wave ships
  (see changelog policy below).

---

## Changelog

Releases follow a **beta train**: while only fixes land, versions increment `2.2.0-beta-1`,
`2.2.0-beta-2`, …; the next feature wave starts a new minor.

### 2.2.0-beta-3
- **Fixed — downloads could not be cancelled on a weak network and removed entries came back:**
  pause/cancel/delete now interrupt the transfer immediately instead of waiting for the stalled
  connection to return; a download removed from the list can no longer resurrect itself, and a
  file is never deleted underneath a running transfer (which could leave a "finished" download
  pointing at nothing — "file does not exist" when opened).
- **Fixed — address-bar suggestions:** the "Go to" row for a typed host and the new "Execute" row
  for any `:` input (scheme URLs included) now appear FIRST, so backspacing `https://` off a URL
  reliably shows the open-as-link suggestion instead of only "Search for".
- **New — connectivity feedback:** an Online/Offline chip pops up at the bottom edge for ~2.5 s
  whenever the connection is lost or restored (Chrome-style awareness without the offline page).
- **Changed — download notifications:** the permanent progress notification is gone. While the app
  is open a download card slides up from the bottom for exactly 5 seconds (tap or drag down opens
  the Downloads screen, any other drag dismisses it); the shade keeps only paused and finished
  notifications.
- **Changed — release notes:** every GitHub release now states what changed and what was fixed
  directly in the release body (extracted from this changelog) instead of linking to it.

### 2.2.0-beta-2
- **Address bar: any `:` input is executable.** Instead of an ever-growing per-scheme allowlist,
  any typed command/URL containing a colon (`file:`, `http:`, `data:`, `intent:`, `javascript:`, …)
  is executed as-is and never sent to the search engine; schemeless hosts (`example.com:8080`)
  still get `https://` automatically. `file:` URLs load only inside the projects folder (unchanged
  engine policy). While the address bar is focused, an **Execute ▶ button** appears whenever the
  input contains `:` — one tap runs it (Enter/Go works as before).

### 2.2.0-beta-1
- **Critical fix — tabs disappearing after a restart:** app start-up is now fault-isolated per
  data source, so a single unreadable database row can no longer zero the tab grid, skip
  extension start-up, or leave the session half-restored while cookies and history keep working.
  Oversized page-state blobs are capped when written and re-read row-by-row on restore; every
  launch records a status report (`init_status.txt`) and surfaces any failure through the
  existing Copy/Share "Crash captured" notification, so the exact stack can be reported straight
  from the phone.
- **In-app HTML project editor:** CodeMirror 5 (syntax highlighting, line numbers, Ctrl/Cmd-F find),
  project file tree with create/rename/delete, 800 ms debounced autosave with flush-on-preview/back,
  read-only `project.json`, UTF-8/2 MB/extension allowlist enforcement, and two entry points —
  *Edit* on the projects screen and *Edit project* in the browser menu (visible only on project pages).
- Repository open-sourced: README rewritten for contributors, development notes moved out of the
  source tree, release notes published on every GitHub Release.

### 2.1.10
- Regression fixes: pull-to-refresh on inner scrollers, extension popup touch passthrough, download
  notification actions, tab-grid drop-to-group & mid-gesture drag, selected-tab card styling.
- Page zoom now uses native `fullZoom` through a patched `omni.ja` (works on strict-CSP sites such as Discord).
- Web console → logcat toggle for on-device site diagnostics.
- Performance pass: skipped redundant Gecko IPCs on tab switches, title-only DB writes for SPA title
  churn, throttled download/notifier rebuilds, cold-start instrumentation.

### 2.1.9
- `browser.cookies` and cookie handling addressed per session's own isolated jar.

### 2.1.8
- WebExtension fixes: active-tab wiring, per-session delegates, tab-specific actions, popup lifecycle,
  add-on downloads and install feedback.

### 2.1.7
- HUD removed; fullscreen exit control, address-bar/progress fixes, pull-to-refresh, download
  notifications, full context menu with selection actions, tab-grid gestures with undo, "Add
  extension" by AMO address, add-on details/update check, Incognito naming, confirmation/undo audit.

### 2.1.6
- Fullscreen restoration via a single `applyChrome()`; extension install from pages + AMO catalog;
  host handoff for the Extensions screen.

### 2.1.5
- Fixed TabsSheet inflate crash; removed HUD menu buttons; floating close-fullscreen button.

### 2.1.4
- One stable APK signing key with a CI signer-pinning gate; crash Copy/Share notification.

### 2.1.3
- Header/HUD clean-up, fullscreen nub, crash-trace capture on uncaught exceptions.

### 2.1.2 → 2.0.0
- GeckoView 155 migration (replacing the system WebView), URL-bar suggestions, CI/release
  hardening; 2.0.x introduced session-isolated site permissions, app-owned downloads, tab groups,
  session ordering, `javascript:` handling and WebExtension support.
