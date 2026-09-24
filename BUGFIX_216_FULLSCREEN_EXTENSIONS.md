# BUGFIX_216_FULLSCREEN_EXTENSIONS.md — v2.1.6 (versionCode 14)

Scope: the fullscreen-restoration bug, the duplicate tab checkmark, dead legacy UI, and the
extension install / manager / AMO work. Every item below names the **root cause**, not the symptom.

---

## A1 — Fullscreen: toolbar never comes back (reported symptom)

### Root cause

Chrome visibility was computed in **four different places with four different conditions**, so the
surfaces disagreed with each other:

| Site | Condition it used |
|---|---|
| `setToolbarHidden()` | `topBar.isVisible = !hidden && !inFullScreen` + `hud.onFullscreenChanged(hidden)` |
| `onFullScreenChanged()` | `topBar.isVisible = !fullScreen && !toolbarHidden`, and hid/showed system bars |
| `updateToolbar()` | `progressBar.isVisible = … && !inFullScreen` (ignored `toolbarHidden`) |
| `updateIsolationBanner()` | `!isIsolated && !inFullScreen && !toolbarHidden` (a third combination) |

Three consequences, all reproducible:

1. **Stranded state.** `handleBack()` in HTML5 fullscreen relied *only* on Gecko's asynchronous
   `onFullScreen(false)` reply:
   ```kotlin
   inFullScreen -> { val gs = tab?.geckoSession
                     if (gs != null) gs.exitFullScreen()
                     else if (tab != null) onFullScreenChanged(tab, false)
                     else inFullScreen = false }      // <-- flag cleared, chrome never re-derived
   ```
   If the reply was late, dropped, or arrived after `currentTabId` changed, `onFullScreenChanged()`
   hit `if (tab.id != currentTabId) return` and **discarded it** → `inFullScreen` stayed `true` →
   `topBar` stayed `invisible` for good. That is the "stale UI restoration" the report describes.
   The `else inFullScreen = false` branch cleared the flag without ever re-deriving the surfaces.
2. **`progressBar` never followed `toolbarHidden`** (its condition omitted it), so a progress line
   floated over a "full screen" page.
3. **The HUD was never told about HTML5 fullscreen.** `hud.onFullscreenChanged()` was called only
   from `setToolbarHidden()`, so in HTML5 fullscreen the pill floated over the video and no ✕ nub
   appeared; the pill's hide control was also force-resurrected by `bar.isVisible = !hidden` on
   every toggle (overriding the session-only hide the user had chosen).

### Fix

One function derives everything; every state change funnels through it:

* `applyChrome()` — the single source of truth: `topBar`, isolation banner, `progressBar`,
  `hud.onChromeChanged(hidden)`, system bars. Idempotent and cheap (safe on every progress tick).
  `updateToolbar()` now ends with it; `updateIsolationBanner()` is called only from it; the two
  stray callers in `onCreate`/`switchSession` call `applyChrome()` instead.
* `anyFullScreen = toolbarHidden || inFullScreen` — one definition of "chrome is hidden".
* `exitFullscreen()` — clears **both flags synchronously** and only then asks Gecko to leave HTML5
  fullscreen, so the chrome returns even if Gecko's answer never arrives. Used by `handleBack()`
  and by the nub (new `Actions.hudExitFullscreen()`).
* `onFullScreenChanged()` — strand-proof formula instead of an early return:
  `inFullScreen = fullScreen && tab.id == currentTabId`. A reply arriving after a tab switch (or
  while `currentTabId` is briefly `null`, as during "New session") evaluates to `false` rather than
  being dropped, and always re-applies chrome.
* `systemBarsHidden` cache: system bars still follow **HTML5 fullscreen only** (the HUD's
  toolbar-hidden mode keeps them — unchanged behavior), and the window is only touched on a real
  transition instead of on every progress tick.
* `HudController.onChromeChanged(hidden)` replaces `onFullscreenChanged()`: no `rebuild()` on every
  toggle, no force-resurrect — a pill hidden this session stays hidden (`pillHidden`), while the ✕
  nub shows for **both** fullscreen flavours.

**Regression risk / test:** toggling HUD fullscreen, opening a video, rotating, switching tabs while
fullscreen, Back in each mode, Back with a dropped Gecko reply (background the app mid-fullscreen).
Nothing was removed: all ten HUD items, drag (both axes), the session-only hide, and system-bar
hiding behave as before.

---

## A2 — Duplicate checkmark in the tabs sheet

**Root cause:** `item_tab.xml`'s root `MaterialCardView` is `android:checkable="true"`, so whenever
`card.isChecked = sel` was set Material painted its own end-of-card check icon **in addition to** the
blue `tabCheck` `CheckBox` the sheet binds — two marks for one selection.

**Fix:** `app:checkedIconVisible="false"` on the root card. `card.isChecked` is kept (it drives the
card's selected background), so only the redundant icon is gone. `item_tab_group.xml` is not a
checkable card and is unchanged.

---

## B — Dead legacy UI removed (no behavior change)

* `res/menu/menu_browser.xml` deleted — zero references anywhere (the menu is built programmatically
  in `renderMenu()`).
* `fullscreenContainer` removed from `activity_browser.xml` (+ field + `findViewById`) — a black
  overlay last used by the pre-GeckoView fullscreen path, `visibility=gone` since.
* `Prefs.KEY_HUD_VISIBLE` / `Prefs.hudVisible` deleted (+ the `BrowserCore` pref-listener condition)
  — nothing read or wrote them since the "Show HUD" menu entry was removed in 2.1.5.
* `HudController.setVisible()/isVisible()/customize()/customItems` and its
  `MaterialAlertDialogBuilder` import deleted (no callers); the orphan `hud_customize` string removed.
* Stale comments fixed: `activity_browser.xml` no longer advertises undo/redo header buttons.

---

## C1 — Install robustness

* **`AddonManagerDelegate.onInstallationFailed(WebExtension?, InstallException)` implemented.** It was
  an unimplemented `default` method, so structured failures Gecko raised on its own were dropped
  silently. It now logs `InstallException.code` + the add-on id and refreshes the list. It does *not*
  post a notification: installs started by the app already report through their `install()` promise,
  so one failure still produces exactly one message.
* **Localized failure reasons.** `ExtensionsActivity.describe()` had hardcoded English for 9 of the
  16 Gecko error codes. Moved to a shared top-level `installErrorMessage(context, t)` in
  `ExtensionManager.kt`, mapping **all** verified `ErrorCodes` from GeckoView 155
  (`NETWORK_FAILURE`, `INCORRECT_HASH`, `CORRUPT_FILE`, `FILE_ACCESS`, `SIGNEDSTATE_REQUIRED`,
  `UNEXPECTED_ADDON_TYPE`, `UNEXPECTED_ADDON_VERSION`, `INCORRECT_ID`, `INVALID_DOMAIN`,
  `BLOCKLISTED`, `INCOMPATIBLE`, `UNSUPPORTED_ADDON_TYPE`, `ADMIN_INSTALL_ONLY`, `SOFT_BLOCKED`,
  `USER_CANCELED`, `POSTPONED`) into `res/values/strings_v216.xml`; anything else falls back to the
  platform message. New strings live in a new versioned file, per repo convention.
* **Root cause: installs started from the Extensions screen were auto-denied.** Only `BrowserActivity`
  ever set `core.extensions.host`, and it clears it in `onStop()` — so with the Extensions screen in
  front `host == null` and `onInstallPromptRequest` answered deny, cancelling the install before the
  user could approve. `ExtensionsActivity` now claims the host in `onStart()` and clears it in
  `onStop()` only if it still holds it (`=== this`); `BrowserActivity.onStart()` claims it first on
  the way back, so the handoff is race-free in both directions. It implements the full
  `ExtensionHost` (install/optional prompts, popup session closed to avoid a leak, tabs declined —
  exactly what a null host did before — options handed off via the existing `EXTRA_OPEN_URL` path).

## C2 — `.xpi` links tapped in a page

`TabDelegates.onExternalResponse` now checks the response first: a `.xpi` path or an
`application/x-xpinstall` content type is an **install request**, not a download. The body stream is
closed and the URL goes to `core.extensions.install()` — Gecko downloads, validates manifest and
Mozilla signature, and shows the permission prompt through the foreground host. The result surfaces
via the new `ExtensionHost.onExtensionInstallResult` (default no-op; `BrowserActivity` shows a snack).
If no host is foreground the install is logged and skipped rather than started and auto-denied.
Nothing bypasses signing; nothing is written outside Gecko's install path.

## C3 — AMO catalog (new `extensions/AmoApi.kt`)

* Official **AMO v5** REST API, verified against the live service while implementing:
  `GET /api/v5/addons/search/?q=&platform=android&lang=en-US&page=N` and
  `GET /api/v5/addons/addon/<slug>/?platform=android&lang=en-US`.
  Response shapes used from ground-truth JSON: `results[].{slug,name,summary,current_version,default_locale,average_daily_users,url,icon_url}`,
  `current_version.file.{url,hash,permissions,host_permissions}`, locale-dict text fields.
* Zero new dependencies: `HttpURLConnection` + `org.json`, all blocking calls run under
  `Dispatchers.IO` inside `runCatching`.
* UI stays inside `ExtensionsActivity` (dialog-driven): search box → result list → detail dialog
  (summary, version, users, permissions) → **Install**, which goes through the same `install()` as
  every other path, so **signature validation is untouched**.
* Honest requests: no spoofed Firefox identifiers, no fabricated headers — if GeckoView cannot run
  something, that is documented in `docs/EXTENSIONS.md` instead of faked.

## C4 — `setAllowedInPrivateBrowsing` (implemented) / `update()` (dropped)

* **Implemented**, because it is verified present in GeckoView 155 —
  `WebExtensionController.setAllowedInPrivateBrowsing(WebExtension, boolean): GeckoResult<WebExtension>`
  at `@HandlerThread`, and `ThreadUtils.assertOnHandlerThread()` only requires a Looper, so a
  popup-click handler on the main thread is safe (the same property the shipped `install()` relies
  on). Surfaced as a checkable per-extension overflow item reading
  `metaData.allowedInPrivateBrowsing`. This is a real gap: Gecko defaults it to **false**, so
  installed add-ons never ran in our private sessions (`usePrivateMode(session.isPrivate)`).
* **Dropped, not faked:** there is **no** `WebExtensionController.update(WebExtension)` in GeckoView
  155 (only `WebExtension.update(Download.Info)` for an already-downloaded file and a *private*
  `ExtensionStore.update`). An update button would be decorative, so it is not offered and the
  absence is documented.

## C5 — AMO add-on page → install

`renderMenu()` gains an "Install add-on from this page" entry, shown only when
`AmoApi.slugFromPage(tab.url)` matches an addons.mozilla.org detail page. It fetches the slug's
detail record and calls `install()`. This exists because the page's own button needs
`navigator.mozAddonManager`, which GeckoView does not expose here — the button can render disabled
while the underlying install is perfectly supported.

---

## Validation performed

* `python3` XML parse of **every** file under `app/src/main/res/**` — all pass.
* Dead-symbol greps after the removals: `menu_browser`, `fullscreenContainer`, `KEY_HUD_VISIBLE`,
  `hudVisible`, `onFullscreenChanged`, `hud.setVisible/isVisible/customize`, `hud_customize`,
  `describe(t` → **zero** references.
* Every `R.string.*` referenced from Kotlin and every `@string/*` referenced from XML resolves
  against `res/values/*.xml`; all newly added strings are referenced (no orphans).
* GeckoView 155 APIs used here were read from the official `FIREFOX_155_0_RELEASE` sources
  (`WebExtensionController.java`, `WebExtension.java`) — nothing guessed, nothing newer than the
  pinned dependency `155.0.20260903215306`.
* No local Android SDK here: CI is the compile gate (versionCode 14, `versionName = 2.1.6`).

## DB impact

None. Extensions live in Gecko's profile; Room stays at `version = 5`. No schema, migration, or
entity change — the `hud_visible` preference key removal only affects `SharedPreferences`.
