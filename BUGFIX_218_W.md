# BUGFIX_218_W.md — v2.1.8 (versionCode 16)

Scope: the W‑0…W‑10 extension-fix list, shipped as **one** release. Every item names the **root
cause**, not the symptom. Nothing here changes the extension signing/security model, no Firefox
identifier is spoofed, no UI is restyled, and the horizontal action strip from v2.1.7 stays where it
is — the new sheet is an addition, not a replacement.

The release's headline defect: **tapping an installed add-on did nothing at all.**

---

## W‑1 — Root cause of "tap the add-on and nothing happens"

### Root cause

`WebExtensionController.setTabActive(GeckoSession, boolean)` was **never called anywhere in the
app**. `GeckoSession.setActive()` *was* called from `TabManager.setDisplayed()`, but that is a
different event: it only toggles `docShellIsActive`.

Read from Gecko's own source (GV155, verified from the extracted classes and the upstream files):

```
WebExtensionController.setTabActive(session, active)
  -> session.getEventDispatcher().dispatch("GeckoView:WebExtension:SetTabActive", {active})
GeckoViewTab.onEvent("GeckoView:WebExtension:SetTabActive")
  -> mobileWindowTracker.setTabActive(window, active)
       nativeTab.active = active
       if (active) this._topWindow = weakRef(window)      // ONLY place _topWindow is ever set
ext-android.js:  WindowTracker.topWindow -> mobileWindowTracker.topWindow
                 TabTracker.activeTab   -> windowTracker.topWindow?.tab   // => null, always
```

With `activeTab === null`:

* `TabManager.addActiveTabPermission(nativeTab = tabTracker.activeTab)` and
  `canAccessTab(nativeTab)` dereference `null` → the `GeckoView:BrowserAction:Click` request fails
  inside Gecko before it ever reaches the add-on, and
* `Action.click()`'s `GeckoResult` never resolves → **no popup, no `browserAction.onClicked`, no
  error surfaced anywhere** — a silent no-op.
* `WindowManagerBase.query` returns `[]` when `topWindow` is null, so
  `tabs.query({active: true, currentWindow: true})` — which Cookie‑Editor's popup calls repeatedly —
  returned an empty list for the same reason.

### Fix

* `ExtensionManager.setTabActive(gs, active)` — one call site, wrapped in **its own dedicated
  try/catch that logs** (the only such catch approved for this release): a failure while marking a
  background tab inactive must never abort a tab switch. It logs, never swallows, and is
  never used to hide a crash elsewhere.
* `TabManager.setDisplayed()` now mirrors `gs.setActive(isIt)` with `setTabActive(gs, isIt)` for
  every live session. Order inside the loop is irrelevant — `setTabActive(_, false)` only sets
  `nativeTab.active` and never clears `_topWindow`.
* `TabManager.closeSession()` marks the session inactive before closing it, so Gecko cannot keep
  pointing at a dead tab.

## W‑2 — Per-session delegates were attached to nothing

### Root cause

`SessionFactory.attachDelegates()` is the only call site of `ExtensionManager.attachToSession()`,
and it runs while a session is created — at which moment `_extensions.value` is **still empty**,
because `WebExtensionController.list()` answers asynchronously after `BrowserCore.ready.complete`.
Every session that existed before that moment kept no `SessionTabDelegate` / `ActionDelegate`, so
`browser.tabs.remove`, `browser.tabs.update` and tab-scoped action updates were dropped without a
trace, and the first active-tab marker could be dispatched too early to stick.

### Fix

`ExtensionManager.reattachAll()` re-runs the attach for every live session and re-applies the
active-tab marker, called at the end of every `refresh()` (install / enable / disable / update
check / start). It hops onto `core.scope` (`Main.immediate`) first so `TabManager`'s `tabs` map is
only read from the thread that mutates it.

* `TabManager.liveSessions()` — every tab that holds a `GeckoSession` (same population as
  `liveCount()`).
* `TabManager.displayedSession()` — the `GeckoSession` behind the GeckoView, null on the start page.

## W‑3 — Tab-specific action overrides were discarded

### Root cause

`ActionDelegate.onBrowserAction` / `onPageAction` began with `if (session != null) return`, so every
badge/title/icon Gecko reported for a *specific tab* was thrown away and only the default survived.

### Fix

Overrides are kept per `(extension id, GeckoSession)` in `ExtensionManager.sessionActions`.
`ExtensionManager.actionsFor(displayedSession)` merges the override over the default with
`WebExtension.Action.withDefault()` and feeds **both** the horizontal strip and the new sheet, for
the tab that is actually on screen. `forgetSession()` drops a tab's overrides when its session
closes (called from `closeSession()`), and also removes emptied maps, so the structure cannot grow.

## W‑4 — Popup lifecycle

* **Toggle-close (Q3a):** `openPopup()` returns `null` when a popup surface is already showing.
  Both delegates document that as "no popup will be displayed", and the package-private
  `WebExtension.Action.openPopup(popup, uri)` starts with `if (popup == null) return;` — Gecko shows
  nothing. Previously every click allocated a fresh popup session and stacked another sheet on top.
* **Close on tab switch / background (Q4a):** `TabManager.setDisplayed()` reports a real change
  through the new `BrowserHost.onDisplayedTabChanged(tab)` (default no-op; `BrowserActivity` is the
  only implementer), and `BrowserActivity.onStop()` calls the same helper. Both drop the popup *and*
  the new actions sheet. Re-entrant calls for the same tab do not fire the callback.
* The popup's existing dismiss listener still releases the `GeckoView` session and the
  `GeckoSession`, so nothing leaks on any of these paths. `sheet_extension_popup.xml` is unchanged.

## W‑5 — "Extension actions" sheet (Q1, option C)

New app-menu entry **"Extension actions"** → `sheet_extension_actions.xml`, one labelled row per
add-on that exposes an action (icon, title, badge; disabled add-on or disabled action dimmed to
0.4 — same rule as the strip). One tap dismisses the sheet and runs the real `action.click()`, so
**Gecko** decides what happens next: an add-on with a popup opens its own popup from the bottom, a
script-only add-on gets `browserAction.onClicked` in its background page.

Add-ons with **no** action (background / ad‑block only) are deliberately absent — they have nothing
to trigger and remain reachable from the Extensions screen. The horizontal strip is untouched.

Strings: new `ext_actions` (`values/strings_v218.xml`). The orphaned
`ext_actions = "Extension buttons"` left over from the removed HUD in `values/strings_v202.xml` is
deleted (it collided with the new name and nothing referenced it).

## W‑6 — Extensions screen cleanup

* `menu_extensions.xml`: `action_amo_find` **removed**.
* `ExtensionsActivity`: `amoSearch()` and `amoResults()` deleted; the overflow now has
  *Install by file*, *Add extension by url*, *Browse Firefox Add‑ons*, *Check for updates*,
  *What extensions can and cannot do*.
* `AmoApi.search()` deleted (it became dead); `AmoApi.detail()`, `slugFromPage()` and
  `AmoApi.slugFromPage()` from the app menu are untouched.
* Orphaned strings `ext_amo_find` / `ext_amo_hint` / `ext_amo_search` / `ext_amo_empty` deleted.
* `ext_add` **value** changed to exactly `Add extension by url`; the key `ext_add` is retained, so
  no reference changes.

## W‑7′ — `downloads.download()` ("save file only")

### Root cause

`WebExtensionController.download()` looks for a `DownloadDelegate`. There was none, so the message
went into `mPendingDownload` and stayed there forever: any add-on calling `downloads.download()`
waited on a promise that could never settle.

### Fix

`ExtensionManager.setDownloadDelegate(...)` → `ExtensionDownloadRunner` (new
`extensions/ExtensionDownload.kt`):

* Pre-flight rejects an unsupported scheme and a missing Android 9 storage permission by returning
  `null` from `onDownload` — Gecko turns that into an immediate `downloads.download is not
  supported` rejection.
* Otherwise the promise is answered **exactly once** (`GeckoResult.complete()` throws if called
  twice): `GeckoWebExecutor.fetch` → validate status → pick the name → `WebExtensionController
  .createDownload(id)` (public factory; `Download`'s own constructor is **protected**) → resolve →
  stream → `STATE_COMPLETE` or `STATE_INTERRUPTED`.
* `Download.Info` is a public interface of `@UiThread` getters, implemented as an immutable snapshot
  (`ExtensionDownloadInfo`); `Download.update()` is `@UiThread`, so every progress push goes back
  through `core.scope`. Copying happens on `Dispatchers.IO`.
* Destination: MediaStore Downloads with `IS_PENDING` on API 29+, a file in
  `Environment.DIRECTORY_DOWNLOADS` plus `DownloadManager.addCompletedDownload` on API 28 — the same
  two paths `AppDownloadManager` already uses. `IS_PENDING` is cleared only after the stream is
  closed; a failed download discards the half-written file.
* **`AppDownloadManager.kt` is a zero-diff file.** The extension download is deliberately *not*
  added to the app's downloads list, so the Downloads screen keeps showing page-initiated downloads
  only, and **no Room schema change or migration** is needed.

Documented limits (also in `docs/EXTENSIONS.md`): `saveAs` shows no file chooser, and
`downloads.pause` / `downloads.remove` issued *by* the add-on are not observable, because
`Download.setDelegate` is package-private in GV155.

## W‑8 — No silent install drop

`TabDelegates.onExternalResponse()` logged and returned when `ExtensionManager.host == null`
(an install started with no foreground UI for the permission prompt). It still refuses the install —
auto-denying would be worse — but now also tells the user where it can happen
(`ext_install_no_ui`), instead of making a tap on an `.xpi` link look like the browser did nothing.

## W‑9 — `EXTDBG` diagnostic switch

Temporary `internal const val EXTDBG = false` + `EXTDBG_TAG` in `extensions/ExtensionManager.kt`,
used by the new extension paths. With the flag false the branches are inert and a release build logs
none of it. It never logs cookies, tokens or a URL beyond `scheme://host`. Scheduled for removal
together with its call sites in 2.1.9.

## W‑10 — `docs/EXTENSIONS.md`

* Header now lists 2.1.8.
* The AMO row no longer describes a search dialog (removed in W‑6).
* New rows: active-tab marker, per-session delegates, tab-specific actions, the actions sheet,
  `downloads.download()`.
* The "tab-specific overrides are ignored" limitation is deleted (fixed in W‑3).
* `downloads` limits (`saveAs`, `pause`/`remove`) stated explicitly.
* API ground-truth table gained: `WebExtensionController.setTabActive`,
  `WebExtensionController.createDownload`, `WebExtension.Download.setDelegate`,
  `WebExtension.Download.Info`, `ActionDelegate.onTogglePopup`/`onOpenPopup` returning `null`.
* Next steps note the planned removal of `EXTDBG`.

---

## Validation performed

* `python3` XML parse — **107/107** resource files + `AndroidManifest.xml` well-formed.
* Resource audit — 483 names, no duplicate within the same qualifier directory; every `R.string` /
  `R.id` / `R.layout` / `R.menu` / `R.drawable` referenced from Kotlin resolves (with `android.R.*`
  excluded); every `@string` / `@layout` / `@menu` inside XML resolves.
* Delimiter balance — all **55** `.kt` files pass a real Kotlin scanner (comments, char/string
  literals, raw strings and `${…}` templates).
* Token scan — `git grep` for `ghp_…` / GitHub token patterns: **clean**.
* **CI is the only compile gate** (no local Android SDK). The 2.1.8 commit must be green before the
  tag.

## On-device checklist (W‑0) — *pending, must be confirmed before calling the behaviour fixed*

These were decided from Gecko source, not from a running device, and are recorded as **unverified**
until someone runs them:

1. Install Cookie‑Editor 1.13.0 (MV3) and Tampermonkey 5.5.0 (MV2); record the install result and
   `InstallException.code` on any failure.
2. `runtime.getBrowserInfo()` on Android — resolves or not.
3. `browser.notifications`, `contextualIdentities`, `proxy`, `commands`, `contextMenus` — present or
   absent.
4. Whether an add-on actually reaches `downloads.download()` now, and whether the file lands in the
   public Downloads folder with `downloads.onChanged` firing.
5. `action.click()` opens Cookie‑Editor's popup on the first tap, closes it on the second, and closes
   it on a tab switch and on Home.
6. `tabs.query({active: true, currentWindow: true})` from inside a popup returns exactly one tab.
7. `.xpi` link tapped with the Extensions screen in front shows the new toast rather than doing
   nothing.
