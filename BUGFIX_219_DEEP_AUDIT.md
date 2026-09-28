# MultiSessionBrowser 2.1.9 — Deep audit report (§20 final output)

**Run:** investigation + source-level fixes only. **No APK/AAB was built, no release was packaged,
nothing was committed or pushed.** CI is the only compile gate (no Android SDK on this host).

**Ground truth:** every GeckoView claim below comes from the shipped dependency
`org.mozilla.geckoview:geckoview-155.0.20260903215306`, extracted from `-sources.jar` and from the
AAR's `assets/omni.ja` into `/tmp/opencode/gv155src`, `/tmp/opencode/omni` and summarised in
`/tmp/opencode/wp0.md`. Nothing was assumed from generic Firefox or Chromium knowledge.

**Companion document:** `BUGFIX_219_SCALE_GESTURES_EXTENSIONS.md` holds the detailed WP0–WP7
implementation notes (prompt controller proof, cookie partitioning proof, GV155 API surface,
page-scale design, PTR/drawer design). This file is the audit verdict across all 20 sections.

---

## 1. Bugs actually found, and their root causes

### 1.1 `Cannot confirm/dismiss a Prompt twice.` — **critical, root-caused**

The crash was reproduced *from the source*, not guessed. In the shipped 2.1.8 tree
(`git show HEAD`, commit `2b801f7`) `BrowserPromptDelegate.kt` line 259 reads:

```kotlin
dialog.setOnDismissListener { finish(prompt.dismiss()) }
```

with

```kotlin
val finish: (PromptResponse) -> Unit = { r -> if (!finished) { finished = true; result.complete(r) } }
```

`finish` took an **already-evaluated value**, so Kotlin evaluates `prompt.dismiss()` *as the
argument*. The sequence when the user tapped a prompt button was therefore:

1. button handler `finish(prompt.dismiss())` → `prompt.dismiss()` **#1** → `BasePrompt.complete()`
   sets `mIsCompleted`, `finished = true`, `result.complete(...)`;
2. Android's `AlertDialog` then dismisses itself, firing `setOnDismissListener` →
   `finish(prompt.dismiss())` → `prompt.dismiss()` **#2** → `complete()` sees `mIsCompleted` →
   `RuntimeException: Cannot confirm/dismiss a Prompt twice.`

`finished` guarded only `result.complete()`, never `prompt.dismiss()`. The reported frame
`show$lambda$1` at `BrowserPromptDelegate.kt:259` is exactly that listener — the line number and
frame name match HEAD, so the crash is from the shipped build, not from the working tree.

GeckoView ground truth that fixes the design (`wp0.md §1`):
`BasePrompt.complete()` is private and reached **only** from `confirm()`/`dismiss()`, and
`PromptResponse` has a package-private constructor — so *this class* is the only thing that can
ever complete a prompt. A single `UNRESOLVED → ANSWERED` transition is therefore both necessary
and sufficient.

**Root fix (not a try/catch):** `finish` now takes a **lambda** (`(() -> PromptResponse) -> Unit`)
and every call site is `finish { prompt.dismiss() }`. The lambda body runs only inside
`Resolution.answer()` *after* `answered = true` is claimed, so `prompt.dismiss()` can never be
evaluated twice. `onPromptDismiss` (Gecko withdrawal), `setOnDismissListener` (Android dismissal)
and every button path funnel through that one object. `prompt.isComplete` is a defensive second
check that claims the resolution and returns before touching the prompt.

**Found during the second audit (§17) and fixed:** `onFilePrompt` had **no**
`PromptInstanceDelegate`. If Gecko withdrew the file prompt (page navigated away, session closed)
while the system picker was open, the returned `GeckoResult` was never completed — `PromptController`
does `res.accept(v -> v.dispatch(cb))`, so Gecko would wait forever — and the picker's callback would
later confirm/dismiss a prompt Gecko had already dropped. It now installs the same withdraw guard as
`onDateTimePrompt` and `show()`.

### 1.2 Page-scale / bookmarklet cross-talk in `onPageStart`

`onPageStart` unconditionally did `tab.scrollY = 0` and `tab.appliedScale = null`. Both are only true
for a **new document**. A `javascript:` bookmarklet load runs against the *current* document, so:

* `scrollY` was reset to 0 while the page was still scrolled → `canRefresh()` in
  `PullRefreshFrameLayout` (which is `t.scrollY <= 0 && !fullscreen && !refreshing`) would report
  "at the top" for a scrolled page → a pull could fire a refresh the user did not intend;
* `appliedScale` was reset to null → `PageScale.applyFor` re-issued a scale script after **every**
  bookmarklet even though `d.style.zoom` had not changed (idempotent, but an unnecessary
  `javascript:` load and a progress-bar blip).

**Fix:** both resets are skipped when `url.startsWith("javascript:")`. The `tab.url` assignment was
already guarded; that guard is unchanged.

### 1.3 Pull-to-refresh could charge distance without ever intercepting

`onTouchEvent` was gated on `if (!armed && !pulling)`. `armed` is set at `ACTION_DOWN` and stays
true for the whole gesture even when the axis decision says HORIZONTAL/undecided and we never
intercept. If the GeckoView ever declined a `MOVE`, the parent's `onTouchEvent` would accumulate
`pullDistance` in silence and `ACTION_UP` could fire `onRefresh` with **no indicator ever shown**.

**Fix:** the gate is now `if (!pulling)`. Only a gesture this view actually intercepted
(`return true` from `onInterceptTouchEvent`) may move the indicator or reload.

### 1.4 Drawer axis decision was too permissive

`axis = if (dy > dx) VERTICAL else HORIZONTAL` declared HORIZONTAL on any gesture whose horizontal
travel won by even one pixel — e.g. `dx = 33, dy = 32` moved the drawer. That is precisely the
reported "drag the sidebar vertically and it slides horizontally".

**Fix:** mirror the pull-to-refresh rule — HORIZONTAL only when `dx > dy * 1.5`. A drag that leans
vertical is VERTICAL, and VERTICAL never calls `super.onInterceptTouchEvent`, so DrawerLayout's
ViewDragHelper never sees it. `UP`/`CANCEL` are still forwarded to `super` first.

### 1.5 Fullscreen exit button was unreadable in the light theme

The cross was tinted `?attr/colorOnSurface` on a `#CC202124` disc. `Theme.MultiSessionBrowser`
parents `Theme.Material3.DayNight.NoActionBar`, so in the **light** theme `colorOnSurface` is
near-black → a dark cross on a dark disc. The disc itself had no border, so on a dark page it
disappeared entirely.

**Fix:** disc stays dark and neutral (`oval`, `#CC202124`) with a `1.5dp #B3FFFFFF` ring so it is
visible on dark pages, and the cross is hard-tinted `@android:color/white` so it is always readable
on the disc. Size 48dp → **40dp** as requested (trade-off noted in §12).

### 1.6 Context menu gaps

Images had no "background tab" entry and media had no "in this tab"/"background tab" entry, so those
elements offered fewer actions than links already had. Added (three new strings, existing
`openForeground` / `openBackground` / `navigate` helpers, no new code paths).

### 1.7 Things audited and found **already correct** (verified, not re-fixed)

| Area | Verdict | Evidence |
|---|---|---|
| §7 bottom HUD | Removed completely, not hidden | `menu_browser.xml` deleted; no `hud*` resource, layout or id anywhere; only prose references in README/docs |
| §14 `Delete "%1$s"?` | Already fixed | Programmatic scan of all 65 format strings vs all 76 `R.string.` usages: **0** usages without arguments. `delete_session_title` is only used as `getString(R.string.delete_session_title, session.name)` |
| §13 Home button | Present | `homeButton` in `activity_browser.xml`, navigates the current tab to `homeUrl()`, no new tab |
| §12 Share labels | Split done | `share_page` ("Share page") and `share_app` are separate menu entries; `shareUrl()` uses `share_page` |
| §15 Incognito | Done | `IncognitoNotifier` persistent "Incognito session active" + `IncognitoCloseReceiver` deletes the session from the notification; user-facing strings say Incognito |
| §10 Download notifications | Done | `DownloadNotifier`: progress channel (LOW, throttled ~1/s, pause action), finished channel (DEFAULT, tap → Downloads, cancel), failure state, `cancel()` on completion, `POST_NOTIFICATIONS` in the manifest, `canPost()` gates Android 13+ |
| §8 selection indicator | Fixed in 2.1.6 | `BUGFIX_216_FULLSCREEN_EXTENSIONS.md` A2: card `app:checkedIconVisible="false"`, only `tabCheck` renders |
| §8 Select All ↔ Deselect All | Correct | `R.string.clear_selection` = **"Deselect all"** |
| §8/§16 swipe, drag-to-group, undo | Present | `ItemTouchHelper` with swipe disabled for pinned tabs, red swipe background, 600 ms hold-to-group, `snackUndo` |
| §9 stale address bar | Fixed in 2.1.7 (F) | trace below (§2.9) |
| §F Add-ons browsing | Real AMO link | menu item `action_browse_amo` → `openInBrowser("https://addons.mozilla.org/android/")`; no fake search UI, no hardcoded list |
| §11 text actions | Covered | `BasicSelectionActionDelegate` gives Cut/Copy/Paste/Select all/system "process text" (translate/define); the app appends Share + Search the web |

---

## 2. Section-by-section findings

### §1 Extensions

**Install.** Three real paths, all Gecko-owned: AMO detail page, AMO "download file" `.xpi`
(`ExtensionManager.installFromUrl`), local file. `AddonManagerDelegate.onInstallationFailed` is
implemented and maps **all 16** GV155 `ErrorCodes`; the install prompt shows the requested
permissions, required origins and data-collection list from `WebExtension.MetaData`.

**Prompts.** All three `WebExtensionController.PromptDelegate` callbacks are implemented and each
returns a `GeckoResult` completed **exactly once**: `onInstallPromptRequest`,
`onUpdatePrompt`, `onOptionalPrompt`. No host ⇒ explicit deny/`GeckoResult.deny()` (logged), never a
dropped callback.

**Popup / action.** Popup is tied to the displayed `GeckoSession`, closes on tab switch and on
`onStop()`, 420dp wide, opens from the bottom; extensions with no action are deliberately absent
from the action list (nothing to trigger). "Extension actions" sheet runs the real `action.click()`.

**Cookies — the exact, source-proven problem (`wp0.md §2`):**

* `GeckoSessionSettings.Builder.contextId` → `StorageController.createSafeSessionContextId` →
  `"gvctx"+hex`, exposed by `geckoview.js:442` as the browser attribute /
  origin attribute **`geckoViewSessionContextId`** on the content principal — hence on **every**
  cookie, localStorage and permission of that session. That is what makes session isolation work.
* `ext-toolkit.js` cookie store ids are only `firefox-default`, `firefox-private`,
  `firefox-container-<n>`; on Android `getContainerForCookieStoreId` is `parseInt` only
  ("TODO: Bug 1643740, support ContextualIdentityService on Android").
* `ext-cookies.js`'s `oaFromDetails` builds `{userContextId, privateBrowsingId, firstPartyDomain,
  partitionKey}` — it **never** carries `geckoViewSessionContextId`, and `convertCookie` derives
  `storeId` from the same two fields.
* Match polarity is proven by two independent callers in `omni.ja`: marionette
  `cookie.sys.mjs` calls `getCookiesFromHost(host, {})` **and then again** with
  `{partitionKey}` and concatenates, and devtools `storage/cookies.js` explicitly unions an
  origin-attributes list with the comment "so we can get cookies from all jars".
  ⇒ `getCookiesFromHost(host, oa)` is an **exact** match (absent keys = default).

**Conclusion — two independent breaks, both inside signed Gecko code (`Appendix A` has the
line-by-line chain):**

* **Break 1 — the visible symptom.** `GeckoViewTab.userContextId` returns the *raw*
  `unsafeSessionContextId` (`GeckoSessionSettings.setContextId()` stores both the raw string and its
  `"gvctx"+hex` form), so `ext-toolkit.js getCookieStoreIdForTab()` reports
  `cookieStoreId = "firefox-container-session-<id>"` for **every** one of our tabs. Android's
  `getContainerForCookieStoreId()` is `parseInt(containerId, 10)` → `NaN`, and `ext-cookies.js
  query()` then **swallows** the error — "fail silently (by not returning any results) instead of
  throwing an error" — so `getAll()` resolves `[]`, `remove()` resolves `null`, `set()` rejects.
  Cookie-Editor always passes that store id (`cookieHandlerPopup.js` → `tabs.query({active:true,
  currentWindow:true})`, then `genericCookieHandler.js` → `cookies.getAll({url, storeId:
  currentTab.cookieStoreId})` and `storeId` on every `cookies.set`), which is why the popup opens
  and renders but lists nothing and cannot write. Granting `cookies` + host permissions changes
  nothing: the throw happens inside `oaFromDetails()` before any cookie is read or written.
* **Break 2 — why a "better" store id would not help either.** Even with a parseable store id the
  filter is `{userContextId, privateBrowsingId, firstPartyDomain, partitionKey}` and never carries
  `geckoViewSessionContextId`, while `getCookiesFromHost()` matches an origin-attributes jar
  exactly. The API can therefore only ever reach the default / private / `userContextId` jars —
  never a `gvctx…` jar.

This is a hard limit of signed `ext-cookies.js` inside `omni.ja`. **Session isolation was not
weakened to make Cookie-Editor work** — mixing cookies across sessions would defeat the product's
reason to exist.

> **RESOLVED IN v2.1.9 — the analysis above is now the *before* state.**
> The "cannot be patched" premise was wrong: `omni.ja` lives in the **app's** `assets/`, it is not
> separately signed, and nothing in Gecko verifies its integrity, so it is a normal build input. It
> is no longer a hard limit.
>
> `tools/patch_omni_cookies.py` rewrites three files inside it:
>
> * `modules/GeckoViewTab.sys.mjs` — adds `get sessionContextId()` returning the **safe**
>   `"gvctx"+hex` id (`GeckoSessionSettings.setContextId()` writes both forms; `settings.sessionContextId`
>   is updated by `GeckoViewSettings.onSettingsUpdate()` right next to `unsafeSessionContextId`).
>   Break 1 is closed because the safe form is now available where the store id is built.
> * `ext-toolkit.js` — two new stores, `firefox-gvctx-<safeId>` / `firefox-gvctxp-<safeId>`, plus
>   `getCookieStoreIdForSessionContext()`, `getSessionContextForCookieStoreId()`,
>   `isSessionContextCookieStoreId()`; `getCookieStoreIdForTab()`, `getCookieStoreIdForOriginAttributes()`,
>   `isValidCookieStoreId()` and `getOriginAttributesPatternForCookieStoreId()` all honour them.
>   Every `ext-*.js` caller (`ext-browsingData`, `WebRequest`, `ProxyChannelFilter`, `ext-tabs-base`)
>   picks this up for free.
> * `ext-cookies.js` — `oaFromDetails()` decodes the store id into
>   `originAttributes.geckoViewSessionContextId` (break 2 closed: the filter can finally express the
>   jar), `convertCookie()` reports the store id back from `cookie.originAttributes.*`, and
>   `getAllCookieStores()` reports the private store correctly. The `context.privateBrowsingAllowed`
>   gate and `checkSetCookiePermissions()` are untouched.
>
> The read path needed no new mechanism: `OriginAttributes` **is-a** `dom::OriginAttributesDictionary`
> (`caps/OriginAttributes.h`), which declares `geckoViewSessionContextId`, so
> `Cookie::GetOriginAttributes()` → `ToJSValue()` already surfaces it to script. Isolation is
> unchanged — `OriginAttributes::Hash()` and `Equals()` both include `mGeckoViewSessionContextId`, so
> `CookieStorage::GetCookiesFromHost()` still matches a jar exactly and a wrong/missing context
> yields **no** cookies rather than another session's.
>
> The patched file is committed at `app/src/main/assets/omni.ja`; CI re-hashes `assets/omni.ja` out of
> every built APK (step *"Verify packaged omni.ja carries the cookie patch"*) so a merge-priority
> surprise fails the run instead of shipping a build where cookies silently regress.

*Correction applied in this run:* the previous wording here said `cookies.set()` "writes into the
*default* jar, i.e. it has no effect on the page". That is only true for an add-on that omits
`storeId`. For Cookie-Editor, break 1 rejects the call first, so the write fails loudly rather than
silently landing in the wrong jar. The other open question — whether `getAll()` leaks *other*
sessions' cookies — is now answered too: Cookie-Editor always passes `url` **and** `storeId`, so it
takes the exact `getCookiesFromHost()` branch and can only see the **default** jar (guaranteed
empty, never a cross-session merge). The pattern fallback `getCookiesWithOriginAttributes()` *is*
unscoped — `nsICookieManager.idl`: pass `'{}'` to match any — but Cookie-Editor never reaches it.
Both points are reflected in `docs/EXTENSIONS.md`, and both remain on the device checklist as
confirmation rather than as open questions.

**API matrix (`wp0.md §3`).** Present in GV155: activityLog, alarms, android, backgroundPage,
browserAction, browserSettings, browsingData, clipboard, contentScripts, cookies,
declarativeNetRequest, dns, downloads, extension, i18n, idle, management, networkStatus,
notifications, pageAction, permissions, privacy, protocolHandlers, proxy, runtime, scripting,
storage, tabs, theme, userScripts, webNavigation, webRequest.
**Absent:** devtools, bookmarks, history, contextMenus, commands, topSites, sessions, find,
geolocation, search, fullscreen, sidebarAction, omnibox. `runtime.getBrowserInfo` **exists**
(`ext-runtime.js:335`). Nothing is faked for an absent API; unsupported calls come from signed
Gecko code and fail there rather than being answered by the app.

**Permissions.** The six `WebExtension.MetaData` grant arrays are surfaced: `logGrants()` prints
permission/origin **names only** (never values) and only when `EXTDBG` is on; the Extensions
details dialog now shows `requiredOrigins` and `grantedOptionalPermissions`, so "what was asked"
and "what was actually granted" are visibly different instead of silently conflated.
GeckoView's permission storage is itself session-context aware
(`GetPermissionsByURI`/`SetPermissionByURI` take `contextId` and build the principal with
`{geckoViewSessionContextId, privateBrowsingId}`), which is why a grant made in one session does not
leak into another — by design.

### §2 Prompt state machine

Every terminal transition now goes through exactly one `Resolution` per prompt. Entry points:
`onAlertPrompt`, `onButtonPrompt`, `onTextPrompt`, `onBeforeUnloadPrompt`, `onRepostConfirmPrompt`,
`onAuthPrompt`, `onChoicePrompt`, `onColorPrompt` (all via `show()`), `onDateTimePrompt`,
`onFilePrompt` (own `Resolution` + withdraw guard), `onPopupPrompt`, `onSharePrompt` (via
`answerNow`). No `.confirm()`/`.dismiss()` call exists anywhere outside a lambda handed to
`answer()`/`finish()`/`answerNow()`. Zero new `try`/`catch` blocks were added (`git diff` scan).

### §3 Page scale

Mechanism: CSS `zoom` on `document.documentElement`, applied through the existing, proven
`javascript:` in-page loader. `zoom` (not `transform: scale`) keeps real layout — media queries,
reflow and hit-testing follow — so desktop/mobile mode, extension pages and internal pages are
unaffected because they are never targeted (`SKIPPED_SCHEMES` = about, resource, moz-extension,
javascript, view-source, chrome; plus start page and error page).

Loop protection, verified end-to-end:

* `Tab.pendingScript` (user bookmarklet) and `Tab.internalScript` (scale) are **separate slots**;
  `onLoadRequest` matches each against its own slot, so neither can consume the other, and a
  page-initiated `javascript:` still finds both empty and stays denied.
* `onPageStart` keys on `tab.internalLoad`, **never on the reported URL** (a `javascript:` load may
  report either the script or the page it ran against).
* `onPageStop` for an internal load clears the marker and **returns without re-entering `PageScale`**
  — that is the loop guard (apply → stop → apply → stop can never start).
* `onLocationChange` early-returns while `internalLoad` and for `javascript:` URLs; `javascript:` is
  never written to `tab.url`, so history, the loading bar and the address bar are untouched.
* `onLoadRequest` clears the marker for any non-`javascript` scheme, so a script Gecko never issues
  cannot swallow the next real page.
* `PageScale.applyFor` refuses while `tab.isLoading`, and no-ops when `appliedScale == want`, so
  repeated identical requests issue **no** load at all.
* `onPageStop` for a **bookmarklet** is a real page-stop for the current document; with §1.2's fix
  `appliedScale` survives it and the scale is not re-issued.

Storage: per-site rule is a row in the existing `site_permissions` table, type
`PAGE_SCALE("page_scale", geckoType = null)`, keyed `(sessionId, origin)`, value = 50…200.
`ASK` = no rule = global default; `set(..., ASK)` deletes the row. **No Room migration** (version
stays 5). Global default = `Prefs.KEY_PAGE_SCALE` + `ListPreference` + `arrays.xml`
50…200 in steps of 10. Global = what a site with no rule shows; per-site = an override for exactly
that `(session, origin)`; switching a session never reveals another session's rule.

### §4 Pull-to-refresh

Four independent gates, none of which disables the gesture: top trigger band (top 40%, min 96dp);
axis decided once after 48dp with `|dy| > |dx|*1.5`; threshold raised 96dp → 160dp with a visible
spinner fill; `canRefresh()` re-checked at interception **and** at release; `ACTION_CANCEL` always
aborts. Nested scrolling is deliberately not used (GeckoView is not assumed to implement the
nested-scroll child protocol), so no unverifiable API is depended on. Plus §1.3's gate fix.

### §5 Sidebar / drawer

`GestureDrawerLayout : DrawerLayout` is the new root of `activity_browser.xml`. It adds **no** second
recognizer: it only *declines* to intercept. `UNDECIDED → axis` after `2 × scaledTouchSlop` with the
1.5× horizontal bias; VERTICAL returns `false` without calling `super`, so page scroll, list scroll
inside the drawer, taps, long-presses and nested containers are untouched; `UP`/`CANCEL` always go to
`super` first so `ViewDragHelper` is never left mid-drag. Swiping the drawer open/closed still works
because a deliberate horizontal swipe has `dx ≫ dy`.

### §6 Fullscreen

`handleBack()` explicitly does **not** exit fullscreen (commented invariant): Back keeps its meaning
→ drawers → URL focus → page back → close tab → background the app, fullscreen intact. The floating
button is the only exit, via `exitFullscreen()` which clears `toolbarHidden` and `inFullScreen`
synchronously and asks Gecko to leave HTML5 fullscreen. All chrome visibility is derived in one
place (`applyChrome()`), which is what prevents the old "exiting fullscreen restores the legacy UI"
regression — no legacy UI exists to restore. Button changes in §1.5.

### §7 HUD — clean, nothing to delete

### §8 / §16 Tab grid — see §1.7 table

### §9 Search-bar state — traced, already correct

`suggestionPopup` click → `navigate(item.url)` → `exitSearchMode()` (clears focus via
`focusHolder.requestFocus()` + `clearFocus()` fallback) → `loadInTab()` → sets
`forceToolbarUrl = url` → `showTab()` → `exitSearchMode()` → `updateToolbar()`.

Two branches in `updateToolbar`: if `forceToolbarUrl != null` it is consumed **once** and written
regardless of focus ("the navigation decides the text exactly once, even while the bar still has
focus"); otherwise the text is only written when the bar is **not** focused, so typing is never
clobbered. Because `forceToolbarUrl` is set before `showTab`, the suggestion path always takes the
first branch — including the case where `clearFocus()` fails while the IME window still holds focus.
SPA/location updates take the second branch once the user has left the bar. No `setText` on every
progress tick (the write is guarded by `text != current`).

### §10–§16 — see the §1.7 table

---

## 3. Exact files/components changed

**Changed this run (after the WP0–WP7 work):**

| File | Why |
|---|---|
| `engine/BrowserPromptDelegate.kt` | withdraw guard on `onFilePrompt` (§1.1) |
| `engine/TabDelegates.kt` | `onPageStart` no longer resets `scrollY`/`appliedScale` for `javascript:` loads (§1.2) |
| `ui/browser/PullRefreshFrameLayout.kt` | `onTouchEvent` gated on `pulling`, not `armed` (§1.3) |
| `ui/browser/GestureDrawerLayout.kt` | 1.5× horizontal bias for the axis decision (§1.4) |
| `res/drawable/bg_exit_fullscreen.xml` | oval disc + light ring (§1.5) |
| `res/layout/activity_browser.xml` | exit button 40dp + forced white cross (§1.5) |
| `ui/browser/BrowserActivity.kt` | 3 context-menu entries (§1.6) |
| `res/values/strings_v219.xml` | the 3 new strings |

**Changed earlier in this 2.1.9 cycle (WP0–WP7):**

| File | Why |
|---|---|
| `engine/BrowserPromptDelegate.kt` | single-resolution guard; lambda `finish`; `answerNow`; withdraw guards |
| `extensions/ExtensionManager.kt` | `logGrants()`, explicit `host == null` logging |
| `ui/extensions/ExtensionsActivity.kt` | details dialog shows required origins + granted optional access |
| `ui/browser/BrowserActivity.kt` | `onExtensionNewTab` uses `activeId`; `showPageScaleDialog`; `ic_zoom` menu entry; `onExtensionNewTab` |
| `engine/PageScale.kt` *(new)* | page-scale core (guards, script, percent resolution, per-site set/clear) |
| `tabs/Tab.kt` | `internalScript` / `pendingScript` / `internalLoad` / `appliedScale` slots |
| `tabs/TabManager.kt` | `loadScript` split into `runScript` (user) + `runInternalScript` (app); apply in `setDisplayed` |
| `permissions/SitePermissions.kt` | `PAGE_SCALE` type (no migration) |
| `core/Prefs.kt`, `res/xml/preferences.xml`, `res/values/arrays.xml` | global default 50–200/10 |
| `ui/settings/SettingsActivity.kt` | re-apply the displayed tab's scale when the global default changes |
| `ui/browser/PullRefreshFrameLayout.kt` | trigger band, axis lock, 160dp threshold, re-checks, cancel |
| `ui/browser/GestureDrawerLayout.kt` *(new)* + `res/layout/activity_browser.xml` | drawer gesture ownership |
| `res/drawable/ic_zoom.xml` *(new)* | menu icon |
| `docs/EXTENSIONS.md` | GV155 cookie/permission reality, API surface, `getBrowserInfo`, optional-permission rationale |

**Changed in this documentation pass (docs only — zero Kotlin/XML behaviour changed):**

| File | Why |
|---|---|
| `extensions/ExtensionManager.kt` | KDoc only: the claim that extensions can "read cookies of every contextual identity" was **false**; replaced with the two-break explanation and an explicit "dropping `contextId` = giving up isolation" note |
| `docs/EXTENSIONS.md` | split the `browser.cookies` limitation into break 1 (unparseable store id → silent empty) + break 2 (OA filter cannot express the jar), corrected the `cookies.set()` consequence, and **closed the polarity question** ("no cross-session leak"); added "upgrading does not fix it"; corrected the stale `EXTDBG` next-step bullet (kept, `false` — decision A3, not removed) |
| `BUGFIX_219_DEEP_AUDIT.md` | §174-§176 conclusion rewritten as two proven breaks + explicit correction note; §5 item 1 (a) / (b); new **Appendix A** (line-by-line chain) and **Appendix B** (Bugzilla-ready patch sketch) |

**Changed in the v2.1.9 implementation pass (cookies made real):**

| File | Why |
|---|---|
| `tools/patch_omni_cookies.py` *(new)* | SHA-256-guarded rewrite of three files inside `assets/omni.ja` (`GeckoViewTab`, `ext-toolkit.js`, `ext-cookies.js`); anchors must match **exactly once** or the run aborts; round-trips every untouched entry byte-for-byte |
| `app/src/main/assets/omni.ja` *(new, committed)* | the patched engine asset — the app's own source set, re-hashed out of every built APK by CI |
| `.github/workflows/build-apk.yml` | new step **"Verify packaged omni.ja carries the cookie patch"** — fails the run if the AAR's stock asset outranked ours |
| `app/build.gradle.kts` | `versionCode` 16 → 17, `versionName` 2.1.8 → 2.1.9 |
| `extensions/ExtensionManager.kt` | KDoc: the cookie bullet moved from *Documented limitations* to *what genuinely works*, with the three-file patch and the isolation invariant spelled out |
| `docs/EXTENSIONS.md` | §"Cannot work here" cookie entries replaced by the implementation + two honest residual limitations (explicit `storeId` required, `getAllCookieStores()` enumerates stores) |
| `BUGFIX_219_DEEP_AUDIT.md` | **RESOLVED IN v2.1.9** block, §5 item 1 marked fixed (with the "signed omni.ja" premise corrected), device checklist item 13 rewritten to the new expectation |

---

## 4. What was intentionally left unchanged, and why

1. **`LOCK_MODE_LOCKED_CLOSED` on both drawers** — the product deliberately opens drawers only
   through their buttons; the swipe path is an additional affordance, not a replacement. Removing
   the lock would change a designed behaviour (approval item, §12).
2. **`addOptionalPermissions` / `removeOptionalPermissions` are not called** — Gecko already asks
   through `onOptionalPrompt` and the app answers honestly; driving the controller API as well risks
   granting twice or granting without a user decision (approval item, §12).
3. **No WebView, no GeckoView replacement, no Room migration, no release packaging.**
4. **`git show HEAD` behaviour preserved everywhere it was verified correct**: downloads
   (`AppDownloadManager.kt` has **zero** diff), tab/group persistence, session isolation, JS URL
   execution, extension install flow, 420dp popup, `http://` `.xpi` install, `EXTDBG = false`.
5. **No "Open in private tab" context action** — privacy in this app is a *session* property. Adding
   that entry would have to open a link in a different session, which mixes sessions and violates
   isolation. Incognito stays per-session.
6. **No translation / video-download context action** — translate already arrives for free through
   the system "process text" actions on a selection; protected media has no downloadable file the
   browser may expose, so no entry was invented.
7. **Plain-text long-press menu not added** — GeckoView's selection action mode already owns that
   case (Copy / Share / Search / translate), and `ContextElement.linkText` semantics for
   `TYPE_TEXT` were not confirmed against the GV155 sources, so no entry was guessed.
8. **`EXTDBG = false` kept** — the diagnostic switch is compiled out; removing it would remove the
   only way to see grant state when a user reports a permission problem (approval item, §12).
9. **Stray untracked `MultiSessionBrowser.txt` (1.4 MB) is not ours** — never staged or committed.

---

## 5. GeckoView limitations discovered (source-proven)

1. **`browser.cookies` cannot address a GeckoView session-context partitioned jar — FIXED in
   v2.1.9.** Two independent breaks were found here (this section keeps the original proof);
   `tools/patch_omni_cookies.py` now closes both by rewriting three files inside the app's
   `assets/omni.ja`, and CI re-hashes that file out of every built APK. Pre-fix description:
   (a) `GeckoViewTab.userContextId` exposes the raw `unsafeSessionContextId`, so
   `getCookieStoreIdForTab()` reported `firefox-container-session-<id>`, Android's
   `getContainerForCookieStoreId` is `parseInt` only ("TODO: Bug 1643740") → `NaN`, and
   `ext-cookies.js query()` swallowed `Invalid cookie store id` by returning nothing — `getAll()` →
   `[]`, `remove()` → `null`, `set()` → rejects; (b) even with a valid store id, `oaFromDetails()`
   built only `{userContextId, privateBrowsingId, firstPartyDomain, partitionKey}` and never carried
   `geckoViewSessionContextId`, while `getCookiesFromHost()` is an exact-jar query, so the default /
   private / container jars were the only reachable ones. **The premise "the code lives in signed
   `omni.ja`, cannot be patched and must not be" was wrong** — `omni.ja` is an ordinary, unsigned
   build input inside the app's own `assets/` and Gecko does not verify it; it is now a committed,
   CI-checked build input. Upstream still lacks the round-trip (current mozilla-central's
   `ext-cookies.js` / `ext-toolkit.js` contain no reference to `geckoViewSessionContextId`), so a
   GeckoView upgrade would not have fixed it either. See `Appendix A` / `Appendix B` and the
   **RESOLVED IN v2.1.9** block above.

   *Evidence for the "no integrity check" claim:* `AppConstants.sys.mjs:153` sets
   `OMNIJAR_NAME: "assets/omni.ja"` (a plain path), and neither GeckoView's `classes.jar` nor Gecko's
   own JS contains `MessageDigest`, `SHA256` or `verifyIntegrity` for it — the only integrity
   boundary is the APK signature, which this project pins in `signing/pinned-signer.sha256`.
2. **No page-zoom API at all.** The only knobs are `GeckoRuntimeSettings.setFontSizeFactor`
   (runtime-wide and text-only — would ignore images and break layout) and read-only zoom state.
   Hence the in-page CSS `zoom` approach.
3. **Absent WebExtension APIs** (must not be faked): devtools, bookmarks, history, contextMenus,
   commands, topSites, sessions, find, geolocation, search, fullscreen, sidebarAction, omnibox.
4. **A `javascript:` load may produce no callbacks at all.** If Gecko issues none, `internalLoad`
   stays set until the next real navigation's `onLoadRequest` clears it. Bounded: at most one SPA
   location update may be suppressed, never a loop. Needs on-device confirmation (§14).
5. **`GeckoResult.complete()` throws if called twice** and `GeckoResult.deny()` is the documented
   rejection value — every prompt path must complete exactly once; that constraint is now structural.
6. **No local compile gate** — no `java`/`javap` and no Android SDK on this host; sources had to be
   read from the extracted `-sources.jar`.

---

## 6. Decisions that need your approval

| # | Decision | Status |
|---|---|---|
| A1 | Drawer `LOCK_MODE_LOCKED_CLOSED` kept (drawers open only via their buttons; swipe still works) | approve / remove |
| A2 | `addOptionalPermissions` / `removeOptionalPermissions` deliberately not called (Gecko's `onOptionalPrompt` is the single grant path) | approve / wire |
| A3 | `EXTDBG` kept as an internal `const = false` switch although `docs` says it was "removed in 2.1.9" | enable, keep, or strip + fix doc |
| A4 | Fullscreen exit button **40dp** — smaller as requested, but below the 48dp accessibility guidance | 40 / 44 / 48dp |
| A5 | **Version not bumped** — `versionName` still 2.1.8 / `versionCode` 16; nothing committed | bump to 2.1.9 / 17 + tag, or hold |

Everything else was treated as an ordinary safe fix and was not escalated.

---

## 7. Static validation performed

| Check | Result |
|---|---|
| XML well-formedness (`python3`) | **109 / 109** parsed |
| Resource declarations vs references | 752 declared, 712 referenced, **0 missing** |
| Kotlin delimiter balance (`ktbal.py`, comment/string/template-aware) | **57 / 57 files balanced** (includes the 2 new files) |
| Room schema version | **5** (unchanged — no migration) |
| WebView class references in source | **0** (WebView not reintroduced) |
| Token scan `git grep -nI "ghp_[A-Za-z0-9]"` | **clean (exit 1)** |
| New `try`/`catch` added by this cycle's diff | **0** |
| Format-string audit (65 strings with `%n$s` vs 76 usages) | **0** usages missing arguments |
| HUD resources / ids | **none** |
| Token material in UI/strings/docs | none |
| Markdown backtick balance (this report, `docs/EXTENSIONS.md`) | **even** (1178 / 718), no unclosed span |
| Report structure scan | **1× Appendix A, 1× Appendix B**, every `§…` / `Appendix X` cross-reference resolves |
| Duplicate-appendix regression (caught mid-edit) | **fixed** — a second copy of Appendices A/B had been appended and was merged down to one |

**Documentation pass re-run:** all of the above re-executed after the docs edits — 109 XML parsed,
0 missing resources, 57/57 Kotlin files balanced, token grep clean, 0 new `try`/`catch`.

**Not performed (no local toolchain):** `javac`/`kotlinc`, Lint, APK/AAB build, instrumentation.

---

## 8. What still requires device / CI testing

**CI (must pass first — nothing here has been compiled):**

1. Full compile of the working tree (16 modified + 4 new source/resource files).
2. Lint/assembleDebug on the runner.

**Prompt (`BrowserPromptDelegate`):**

3. `alert()` / `confirm()` / `prompt()` — tap OK, tap Cancel, tap outside, tap Back: **no**
   `Cannot confirm/dismiss a Prompt twice`, no hang.
4. Navigate away while each prompt type is open (alert, select, date/time, HTTP auth, file picker):
   dialog closes, no pending state, page stays usable.
5. File chooser: open picker → press Back/cancel → callback fires once; open picker → navigate away
   → close picker → no crash (this is the new guard).

**Page scale regression script (§3):**

6. Heavy SPA → 130% → 130% again → 150% → navigate normally → `javascript:` bookmarklet →
   change scale → pushState → reload → switch tab/session → hibernate/restore → close/reopen tab.
   **No infinite loop, no progress-bar flash, scroll preserved, history intact.**
7. Global default change in Settings re-applies the displayed tab; background tab picks it up when
   shown; per-site rule wins; "Use the default for this site" restores it; two sessions can hold
   different rules for the same origin.
8. Desktop mode + scale together; scale does not apply to start page, error page, `about:`,
   `resource://` or `moz-extension://`.

**Gestures:**

9. Discord/long page: fast fling from the middle, scroll up/down at the top, horizontal flick,
   diagonal drag → **never** refreshes; deliberate pull from the top band past 160dp → refreshes;
   release before threshold → cancels; rotate mid-pull.
10. Sidebar: vertical drag inside/over the drawer → drawer stays put; horizontal swipe from the
    edge → opens/closes; diagonal swipe → does not nudge the drawer; list scroll inside the drawer;
    taps and long-press unchanged; both drawers.

**Fullscreen / UI:**

11. Exit button readable on a black video and on a white page, in light **and** dark theme;
    drag it to every corner; tap vs drag; Back does *not* exit fullscreen; exiting does not
    resurrect any legacy UI.

**Extensions:**

12. Cookie-Editor + Tampermonkey install → `InstallException.code` on failure; `runtime.getBrowserInfo`;
    `browser.notifications`, `contextualIdentities`, `proxy`, `commands`, `contextMenus` behave as
    absent (fail cleanly, no browser crash); `downloads.download()` settles its promise.
13. **Cookies — the feature v2.1.9 adds (replaces the old "confirm it lists nothing" check):**
    1. *Engine boots with the patched asset:* launch the app and load a page. `assets/omni.ja` is the
       engine's own jar — if Gecko rejected it nothing would render. This is the first check.
    2. *CI proved the merge:* the run must show **"Verify packaged omni.ja carries the cookie patch"**
       green, printing `OK <apk> -> omni.ja 7a2f8541…` for every debug and release APK.
    3. *Read:* Cookie-Editor on a logged-in session (e.g. Discord) lists that session's cookies —
       name, domain, path, expiry, `httpOnly`, `secure`, `sameSite` — instead of an empty list.
    4. *Write + edit + remove:* add a cookie in the popup, confirm the page sees it
       (`document.cookie` / reload), change its value, then delete it and confirm it is gone. No
       silent no-op: a failed write must surface as an error in the popup.
    5. *Isolation A↔B:* session A logged in, session B logged out on the same site. Cookie-Editor in
       B lists **B's** cookies only and never A's; repeat from A; A stays logged in after B's edits.
    6. *Incognito:* an incognito session's cookies are visible only from that session, and a normal
       session never shows them (nor the reverse).
    7. *`onChanged`:* editing a cookie in A must not make a Cookie-Editor left open in B react.
    8. *Permissions:* with `cookies` denied, or with `cookies` granted but no host permission for the
       site, the list stays empty; granting both makes it populate. `set()` must be refused the same way.
    9. *Documented limitation, not a leak:* `cookies.getAll({})` with no `url`/`storeId` returns `[]`
       — identical to Firefox container semantics, and the correct behaviour is an empty result
       rather than some other session's jar.
14. Popup: opens above the page, receives touch input, 420dp not clipped, closes on tab switch and
    on leaving the Activity; popup tied to the current `GeckoSession`.
15. `.xpi` with `host == null` → logged deny, not a crash; `tabs.query({active:true})` returns the
    displayed tab after the `activeId` change; extension-opened tab becomes the active tab.
16. Optional-permission prompt: allow → `grantedOptional*` grows (visible in the details dialog);
    deny → unchanged; restart → grant persisted.

**Everything else:**

17. All 20 sections of the original brief re-checked on device (tab grid select-all, swipe/undo,
    drag-to-group, empty groups; home; share labels; download notifications on Android 13/14/15;
    Incognito notification + close action; every destructive dialog shows the real object name).

---

## 9. Honest status

* **Fixed in source:** prompt double-dismiss (root cause, with line-level evidence), file-prompt
  withdrawal leak, bookmarklet/scale cross-talk, PTR silent-charge path, drawer axis bias,
  fullscreen button visibility/size, 3 context-menu gaps.
* **Audited and already correct:** HUD removal, `Delete "%1$s"`, home button, share labels,
  Incognito naming + notification, download notifications, selection indicator, Select/Deselect all,
  swipe/drag tab gestures, address-bar staleness, AMO browsing.
* **Proven impossible without breaking session isolation:** WebExtension `browser.cookies` access to
  a GeckoView session-context jar — documented, not faked, not worked around.
* **Verified only statically.** No claim in this report is an on-device verification.

---

## Appendix A — Cookie-Editor root cause: the full chain, step by step

Everything below was read out of the shipped `omni.ja`, the extracted `ext_ce.xpi` and
mozilla-central. Two *independent* breaks; either one alone is fatal.

### A.1 Break 1 — the store id we report cannot be parsed back

| # | Where | What it does |
|---|-------|--------------|
| 1 | `data/db/Entities.kt:29` + `engine/SessionFactory.kt:23` | every browser session gets `contextId = "session-<uuid>"` and passes it to `GeckoSessionSettings.Builder.contextId()` |
| 2 | GV155 `GeckoSessionSettings.java:637-640` | `setContextId()` stores **two** values: `unsafeSessionContextId` = the raw string, `sessionContextId` = `StorageController.createSafeSessionContextId()` = `"gvctx" + hex(bytes)` |
| 3 | GV155 omni `modules/GeckoViewTab.sys.mjs:33-36` | `get userContextId()` returns the **raw** one — `settings.unsafeSessionContextId` |
| 4 | `chrome/toolkit/content/extensions/parent/ext-toolkit.js:34-42` | `getCookieStoreIdForTab(tab)` → `getCookieStoreIdForContainer(tab.userContextId)` → `"firefox-container-session-<uuid>"` |
| 5 | same file, `:75-88` | `getContainerForCookieStoreId()` on Android is `parseInt(containerId, 10)` only ("TODO: Bug 1643740") → `NaN` |
| 6 | `ext-cookies.js:283-360` | `oaFromDetails()` does `if (isNaN(userContextId)) throw new ExtensionUtils.FormatError("Invalid cookie store id")` |
| 7 | `ext-cookies.js:379-384` | `query()` **catches it and returns silently** — "fail silently … instead of throwing an error" |

Net effect for Cookie-Editor, whose `popup/cookieHandlerPopup.js:20` does
`tabs.query({active:true, currentWindow:true})` and `lib/genericCookieHandler.js:26-29` does
`cookies.getAll({url, storeId})` with `:59`
`storeId: cookie.storeId || this.currentTab.cookieStoreId || null` on every `cookies.set`:

* `cookies.getAll()` → resolves `[]` (no error shown, empty list) — the reported symptom;
* `cookies.remove()` → resolves `null`;
* `cookies.set()` → **rejects** with `Invalid cookie store id` (it throws *before*
  `Services.cookies.add()` is reached — it never lands in the default jar, correcting the earlier
  wording of §5);
* granting the `cookies` permission or host permissions changes nothing — the failure is inside
  `oaFromDetails()`, before any cookie is touched. This is why "the popup opens but has no
  cookies" is not a permission problem.

### A.2 Break 2 — even a valid store id could not address the jar

| # | Where | What it does |
|---|-------|--------------|
| 1 | `chrome/geckoview/content/geckoview.js:442` | writes the browser attribute `geckoViewSessionContextId` from `sessionContextId` |
| 2 | `dom/base/nsFrameLoader.cpp:3575` `PopulateOriginContextIdsFromAttributes` | copies it into the frame's `OriginAttributes` |
| 3 | `dom/chrome-webidl/OriginAttributes.webidl:23,32` | the field exists in **both** `OriginAttributesDictionary` and `OriginAttributesPatternDictionary` — it is a real, serialisable origin attribute |
| 4 | `netwerk/cookie/CookieKey.h` `KeyEquals` | the cookie jar key is `(baseDomain, OriginAttributes)` compared by **full struct equality** — so each session has its own jar by construction |
| 5 | `ext-cookies.js:283-360` `oaFromDetails()` | the filter is exactly `{userContextId, privateBrowsingId, firstPartyDomain, partitionKey}` — `geckoViewSessionContextId` is **never** produced |
| 6 | `netwerk/cookie/nsICookieManager.idl` | `getCookiesFromHost()` is documented as an *exact* match: "Cookies stored in a different jar … are not counted"; `countCookiesFromHost(host, {})` == `hasCookiesForSite(host, "{}")` where `'{}'` means "match any" — i.e. absent key = default, not unconstrained |
| 7 | `ext-cookies.js:414` | `query()` chooses `getCookiesFromHost()` whenever `host && !isPattern` — which is always, for Cookie-Editor |

**Polarity (answered):** Cookie-Editor always passes `url` *and* `storeId`, so it takes the exact
`getCookiesFromHost()` path → the default jar only → guaranteed empty, **never another session's
cookies**. The unconstrained pattern path (`getCookiesWithOriginAttributes()` with no `url`/`domain`)
is unreachable from this add-on; only a bare `cookies.getAll({})` would merge jars. Kept on the
device checklist as confirmation.

**Isolation check:** nothing in this run changed `contextId`, cookie storage, the extension
permission flow, or the prompt state machine. Sessions A and B still have disjoint jars; the
extension simply cannot address *either* of them.

---

## Appendix B — What an upstream fix would have to do (Bugzilla-ready)

Nothing below is implemented here; it is written so the report can be attached to an upstream bug
against GeckoView / `toolkit/components/extensions`.

There is no API an app-side caller could use instead: `GeckoSession.CookieController` does not exist
in GV155, `WebExtensionController` has no cookie delegate, `GeckoSessionSettings` has no
`userContextId` setter, no `GeckoView*.sys.mjs` handles a `usercontextid` message, and
`nsICookieManager` is reachable from neither Kotlin nor WebExtension content scripts. The fix has
to land in Gecko:

1. **`ext-toolkit.js getCookieStoreIdForTab()`** — stop funneling a GeckoView session context
   through the *container* namespace. Emit a fourth store id namespace, e.g.
   `firefox-gvctx-<safeid>`, sourced from a new `GeckoViewTab.sessionContextId` (or from
   `userContextId` only when it is numeric, i.e. a genuine container).
2. **`ext-toolkit.js getContainerForCookieStoreId()`** — stop `parseInt`-ing that value back into a
   container number.
3. **`ext-cookies.js oaFromDetails()`** — map that store id to
   `originAttributes.geckoViewSessionContextId` instead of `userContextId`, so the exact-jar query
   targets the session's jar. Keep `firefox-container-<n>` → `userContextId` untouched for desktop.
4. **`convertCookie()` / `convertStoreId()`** — derive `storeId` from `geckoViewSessionContextId`
   when present, so cookies read out of a session jar are labelled with the matching store id
   instead of `firefox-default` (today's mislabelling).
5. **`getAllCookieStores()`** — group tabs by the new namespace too.
6. **`set()` / `remove()` / `onChanged`** — no separate change needed; they all route through
   `oaFromDetails()` / `convertCookie()`.
7. **Permissions** — no new permission is required; reuse the existing `cookies` permission and the
   current origin check.
8. **Test** — set `GeckoSessionSettings.contextId` on two sessions, assert
   `tabs.query()[0].cookieStoreId` round-trips, and assert `cookies.getAll({url, storeId})` returns
   exactly one session's cookies and never the other's.

Estimated size: ~60 lines across three files — `chrome/toolkit/content/extensions/parent/`
(`ext-toolkit.js`, `ext-cookies.js`) plus one line in `modules/GeckoViewTab.sys.mjs`. No schema
change, no migration, no desktop-container regression: every path stays a no-op unless
`geckoViewSessionContextId` is set, which only GeckoView embedders do.

**Upgrading does not help today:** current mozilla-central's `ext-cookies.js` / `ext-toolkit.js`
contain **no reference at all** to `geckoViewSessionContextId`, so the gap still exists upstream. The
right move is to file that bug with §174-§176 and this appendix attached.

**Forking is not an option:** `ext-cookies.js` ships inside signed `omni.ja`, and this project must
never bypass extension signing (`ERROR_SIGNEDSTATE_REQUIRED` is the intended behaviour of
`XPIProvider`) — patching it invalidates the signature for *every* add-on, not just this one. A
GeckoView fork would have to be rebuilt and security-reviewed for every upstream release, which is
not realistic here. Note also that `Bug 1643740` (`ContextualIdentityService` on Android) is a
*different* gap: it would only make numeric container ids round-trip, while our isolation key is
`geckoViewSessionContextId`, which no container id can express.
