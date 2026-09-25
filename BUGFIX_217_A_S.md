# BUGFIX_217_A_S.md — v2.1.7 (versionCode 15)

Scope: the A–S fix list for the Android browser, shipped as **one** release. Every item names the
**root cause**, not the symptom. Nothing here changes the extension signing/security model, no
Firefox identifier is spoofed, and the modern UI (v2.1.0 tab grid, drawers, app menu) is untouched —
only the obsolete bottom HUD is removed.

---

## A + K — The bottom HUD is dead weight, and fullscreen had no way out

### Root cause

`view_hud.xml` + `HudController.kt` kept a floating pill at the bottom of the screen with
`top / bottom / close tab` actions, plus a `hudNub` for the fullscreen escape. Every one of those
actions already exists somewhere else (the toolbar, the app menu, the tab grid), so the pill was a
second copy of the chrome that permanently ate ~60 px of the page — and `Prefs.hudItems` +
`KEY_HUD_ITEMS` + `DEFAULT_HUD_KEYS` still paid for a *customisation* of those duplicates
(`BrowserCore.prefListener` even had to exclude the key from the settings-dirty flag).

Fullscreen exit was the one thing that only the HUD owned: with the pill gone there would have been
no UI way out of HTML5 fullscreen other than Back.

### Fix

* `HudController.kt`, `view_hud.xml`, `bg_hud.xml`, `ic_hud.xml` **deleted**; the `hud` include and
  `hudNub` are gone from `activity_browser.xml`.
* `Prefs.KEY_HUD_ITEMS` / `hudItems` / `DEFAULT_HUD` / `DEFAULT_HUD_KEYS` deleted, and the
  `prefListener` line in `BrowserCore` reduced to `key != Prefs.KEY_ACTIVE_SESSION`.
  (Older stored `hud_items` values are simply never read again — no migration needed for a value
  that nothing reads.)
* New `FullscreenExitButton` (`ui/browser/FullscreenExitButton.kt`): a small circular button,
  bottom-right, **draggable in both axes** so it can be moved off a video's own controls, tap =
  exit. Position is per-Activity instance and never persisted. Visibility is driven from the single
  `applyChrome()` from 2.1.6, so it cannot drift from the actual chrome state.
* 17 `hud_*` strings deleted; `toolbar_hidden_hint` rewritten; HUD wording scrubbed from
  `BrowserActivity`, `AppMenu`, `Tab`, `TabDelegates` comments.

---

## B — "Add extension" only understood raw `.xpi` URLs

### Root cause

`ExtensionsActivity` handed the input straight to `ExtensionManager.install(uri)`, which hands it to
Gecko. Pasting an `addons.mozilla.org/addon/…` **page** (what every user copies first) therefore
sent an HTML document to Gecko's install pipeline, which failed with a misleading error — the AMO
catalog search worked, the address field did not.

### Fix

`installFromInput()` now classifies the input before anything reaches Gecko:

1. `AmoApi.slugFromPage(url)` → AMO add-on page, or
2. `AMO_SLUG.matches(value)` → a bare slug (`ublock-origin`), or
3. otherwise the old direct-`.xpi` path.

Cases 1–2 resolve through the **public AMO v5 API** (`AmoApi.detail`) and show the standard
detail/install dialog, which installs `current_version.file.url`. The file still travels through
`ExtensionManager.install()` → Gecko validates the manifest **and Mozilla's signature**; the path
that performs those checks is untouched. Nothing is spoofed and no check is bypassed.

---

## C — Extension audit: details, updates, and a wrong claim in `docs/EXTENSIONS.md`

### What was wrong

`docs/EXTENSIONS.md` asserted:

> **No update check/button.** GeckoView 155 exposes `WebExtension.update(Download.Info)` … there is
> **no** `WebExtensionController.update(WebExtension)`.

Parsing the extracted GV155 `classes.jar` says the opposite:

| API | GV155 reality |
|---|---|
| `WebExtensionController.update(WebExtension)` | **exists**, returns `GeckoResult<WebExtension>` (proved by `lambda$update$9(GeckoBundle) -> WebExtension`) |
| `WebExtension.update(...)` | does **not** exist (no such method at all) |

The document was wrong in both directions, and the app was missing a feature it could honestly
offer because of that claim.

### Fix

* `ExtensionManager.checkUpdate(ext, onDone)` wraps `controller.update(ext)`; the callback compares
  `metaData.version` before/after, so **"No update available"** and **"Add-on updated"** are literal,
  never guessed. Gecko performs the download and the signature verification itself — the app cannot
  install an unsigned or user-modified build through this path.
* Per-add-on overflow menu gains **Details** (description, version, author, required permissions and
  the stable id — all from `WebExtension.MetaData`, no extra request, nothing invented; an AMO/home
  link appears only when the add-on publishes one) and **Check for updates**.
* Toolbar gains **Browse Firefox Add-ons** → `https://addons.mozilla.org/android/`.
* `docs/EXTENSIONS.md` corrected (both claims above) plus a new **"GeckoView 155 API ground truth"**
  table written from the class files: `BasicSelectionActionDelegate` hooks are *protected non-final*
  (hence `AppSelectionActionDelegate`), `ContextElement` has `linkText` but **no `textContent`** and
  its `extensionMenus` list is package-private, and GV155 has no app-side Translations API — each row
  states what the app therefore does (or deliberately does not claim).

---

## D — Long-press menu and selection toolbar were bare-bones

### Root cause

`onContextMenu` offered four entries and could not express what people actually long-press for: no
foreground/background distinction, nothing for linked files, no share/copy of an image or media
address. Text selection was left to Gecko's stock toolbar with no browser actions.

### Fix

* **Context menu** — grouped entries for link / image / media: *open in a new (foreground) tab*,
  *open in a background tab*, *open in this tab*, *copy*, *share*, *download* (linked file, image,
  media), *copy/share image|media address*, *open externally*. Title = `element.title`, falling back
  to the verified `element.linkText`, then the host. GV155's `ContextElement` carries no
  `textContent`, so a long press on plain text starts a selection — that is engine behaviour, and the
  text actions live one layer up (below).
* **`AppSelectionActionDelegate`** — subclasses Gecko's `BasicSelectionActionDelegate` and *appends*
  **Share** and **Search the web** through the protected, non-final
  `getAllActions() / isActionAvailable(String) / prepareAction(String, MenuItem) /
  performAction(String, MenuItem)`. Every built-in id still falls through to `super`, so Cut / Copy /
  Paste / Select all and the system `PROCESS_TEXT` entries keep Gecko's exact implementation.
* **Transport control** — the app menu shows Play/Pause while `tab.mediaSession` is active
  (`MediaSession.isActive/play/pause`, verified in GV155; stored by `TabDelegates.onActivated/
  onDeactivated`).

---

## E — Downloads only spoke while the app was open

### Root cause

`AppDownloadManager` published progress to a StateFlow and the UI showed a Snackbar; leaving the app
gave no feedback at all, and the Snackbar was removed as soon as another one appeared.

### Fix

* `DownloadNotifier` — two channels (`LOW` progress, `DEFAULT` finished). Per download: ongoing
  notification with a real progress bar (indeterminate when the server sent no length, never a fake
  0%), **Cancel** action; paused → not ongoing, **Resume** action; completed/failed → a result
  notification with the failure reason; cancelled → removed. Progress writes are throttled to one
  per second and never re-sent unchanged (progress is published every ~300 ms).
* `DownloadActionReceiver` — manifest-registered, `exported=false`, explicit intents only.
  The PendingIntent request code is derived from the download id: two PendingIntents that differ only
  in extras are matched as "the same" by Android, so a shared request code would have made one
  download's Cancel kill another.
* In-app `downloadBanner` in the top bar follows the same StateFlow (tapping it opens Downloads).
* Nothing is posted when the app may not post notifications (`POST_NOTIFICATIONS` on 13+, app toggle).

---

## F — The address bar kept showing the previous page

### Root cause

`updateToolbar()` only wrote a URL when it *changed* relative to what the field held, so a load that
finished with an identical/blank URL never corrected the field, and suggestion popups kept their old
query. `exitSearchMode()` could also leave focus (and the stale text) behind, and `runJavaScript`
read the URL from the field instead of the session.

### Fix

A one-shot `forceToolbarUrl` flag: `loadInTab` sets it, `updateToolbar` writes the forced value and
dismisses suggestions once, `exitSearchMode()` clears focus as a fallback, `runJavaScript` forces the
page URL.

---

## G — The loading indicator stayed behind for a beat after the page was ready

### Root cause

The indicator only flipped when Gecko's asynchronous callbacks arrived, so between "load requested"
and "onPageStart/onPageStop" there was no state at all — and the reverse gap existed on tab switch.

### Fix

Optimistic `isLoading/progress` in `loadInTab()` (and reset on the start page) and in `reload()`;
`showTab` resets them in its `catch`; indeterminate progress renders with
`android:indeterminateTint="?attr/colorPrimary"`. All four `onLoadRequest` deny paths now funnel
through `TabDelegates.denyNavigation()` so a denied load can no longer leave the indicator running.

---

## H — No pull-to-refresh

### Root cause

The app had no touch-native refresh; the only way to reload was the menu.

### Fix

`PullRefreshFrameLayout` wraps the GeckoView. Deliberately **touch-based, not nested-scrolling**
(GeckoView is not assumed to implement the nested-scrolling child protocol, so nothing depends on an
API that may not exist). The gesture arms only when `canRefresh()` says the page is at scroll offset
0, not on the start page and not in any fullscreen flavour; releasing past 96 dp fires `onRefresh`
(the menu's `reload()`), the spinner fills with the pull, hides on load-stop/tab-switch and auto-hides
after 20 s so it can never stick. `Tab.scrollY` is updated by the new
`GeckoSession.ScrollDelegate` on `TabDelegates`.

---

## I — Confirmation/undo audit

### Rule applied

| Action | Behaviour |
|---|---|
| Close many tabs, delete a session, delete a group, clear site data, clear all history | **Confirmation** (unchanged) |
| Close one tab (X, card menu, swipe, group "close tabs") | **No dialog, Undo snackbar** (`BrowserActivity.closeTab(tab, undo = true)` / `offerUndo(closed)`) |
| Archive (bulk, single card, whole group) | **No dialog, Undo snackbar** (`snackUndo()` → `unarchiveTabs()`) — archiving is one step away from being undone |
| **Clear the recently-closed list** | **was one tap away from being final → now confirms** (`clear_list_confirm`) |

The multi-tab close confirm was deliberately **kept** and gained Undo; swipe-to-close is the gesture
itself (Chrome/Firefox Android behave the same) so it closes immediately with Undo and is disabled
for pinned tabs.

---

## J — Share page / Share app

`shareUrl` was one function doing two jobs, so "Share app" could not carry its own chooser title or
subject. Now `shareText(text, chooserTitle, subject?)` is the single helper, with
`share_page` / `share_app` chooser titles and `SHARE_APP_URL` (the public release mirror) as the
app entry. `ActivityNotFoundException` → snack instead of a crash.

---

## L — Home

No way back to the start page except the URL bar. `homeButton` in the toolbar (`ic_home` created),
no-op on the start page, plus an app-menu entry.

---

## M — Session delete dialog had no name

`SessionsDrawer.confirmDelete` now uses `getString(R.string.delete_session_title, s.name)`, so the
dialog says *which* session is being deleted.

---

## N — Incognito was called "Private", and nothing said it was running

### Root cause

The user-visible naming disagreed with the feature's own explainer, and an Incognito session looked
exactly like any other one in the tray — easy to keep browsing privately without noticing.

### Fix

* Naming: `private_label/private_session/private_session_name/private_session_explainer` →
  `incognito_label/incognito_session/incognito_session_name/incognito_session_explainer` across the
  strings, both layouts, `SessionsDrawer` and `SessionManager` (the old keys are gone, no duplicates).
* `IncognitoNotifier` — while the **active** session is Incognito, one low-priority, non-ongoing
  notification (no heads-up, no sound) explaining what Incognito does. **Tap = open the app**,
  **"Tap to close" = `core.sessions.delete(id)`** — exactly the sessions-drawer path, which destroys
  the session, its tabs and its profile data (cookies/site data), then switches away. Cancelled the
  moment the active session stops being Incognito and by the finishing Activity, so a stale
  notification cannot outlive the state it describes. `IncognitoCloseReceiver` is
  `exported=false` with explicit intents and refuses to act if the session already changed.

---

## O — Swipe a tab away in the grid

`ItemTouchHelper` gained swipe-to-close: red `onChildDraw` background, `onSwiped` →
`closeTab(tab, undo = true)` + `rebuild()`. Disabled while multi-select is on and **never for pinned
tabs** (`getMovementFlags` returns no swipe flags).

---

## P — Hold-to-group

Dragging a card and letting it rest for **600 ms** on a *different* card highlights that card
(brand-coloured stroke) as the group target; dropping then groups the two. The timer re-arms whenever
the finger moves, and any reorder cancels it — resting is a group intent, moving is a reorder. The
existing target's group wins, then the dragged tab's group, then a new group is created.

---

## Q — Tab-grid selection chrome

`item_tab.xml`'s `tabCheck` gets `android:buttonTint="?attr/colorPrimary"` and
`app:checkedIconVisible="false"` so the checkbox reads as part of the card instead of a stock blue
Android widget (the card already suppressed `checkedIcon`).

---

## R — "Deselect all"

`clear_selection` said something ambiguous; it is now **"Deselect all"**.

---

## S — Validation

Run for this release (no local Android SDK is used anywhere in this project):

* **XML** — every `*.xml` under the repo parses (`python3` `xml.etree`): 105 files, 0 errors.
* **Strings** — every `R.string.*` / `@string/*` reference resolves to a declaration (458 strings);
  **zero** declared-but-unused strings in `strings_v217.xml`.
* **HUD** — `grep -ri hud app/src/main` matches only explanatory comments; no `HudController`,
  `R.id.hud`, `hudNub`, `KEY_HUD_ITEMS`, `hudItems`, `DEFAULT_HUD` symbol survives.
* **Structure** — brace/paren balance over all 54 Kotlin sources under `app/src/main`: 0 imbalances.
* **Prior fixes intact** — single `applyChrome()` source of truth, `em.host === this` host handoff,
  `forceToolbarUrl`, `denyNavigation`, `setRefreshing`, banner/notifier wiring, `offerUndo`.
* **GeckoView API** — every GV API touched in this release was read out of the extracted GV155
  `classes.jar` (`/tmp/opencode/gv155cls/`), not guessed. The two claims this release *changes* are
  documented in the ground-truth table in `docs/EXTENSIONS.md`.
* **Docs** — `README.md` no longer advertises the removed HUD and its WebView-era Features/honesty
  bullets were corrected against the code (live `GeckoSession` LRU, `resource://android/assets/www/`
  local content, isolation = GeckoView `contextId`, web notifications via site permissions, `blob:`
  read from the tab's stream); `docs/ARCHITECTURE.md` §F marks its WebView-era risk list superseded;
  `docs/EXTENSIONS.md` carries the corrected GV155 ground truth and the Incognito naming.
* **CI** — the commit is the compile gate; the annotated tag `2.1.7` == `versionName`.

Deliberately **not** changed: extension signing/security, session isolation, the modern UI, and
`versionName`/`versionCode` until the shipping commit.
