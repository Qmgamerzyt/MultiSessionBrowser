# MultiSessionBrowser 2.1.9 — deep-audit report (§23)

**Scope:** WebView/GeckoView prompt double-dismiss crash, WebExtension permission/cookies compatibility
(GeckoView 155), page scale, pull-to-refresh + sidebar gesture sensitivity.
Workflow: audit → implement → static validation.
**No APK/AAB build, no packaging, nothing committed or pushed.**

Ground truth extracted this run:

- `geckoview-155.0.20260903215306-sources.jar` → `/tmp/opencode/gv155src`
- AAR `assets/omni.ja` (14.9 MB) → `/tmp/opencode/omni`
- consolidated notes: `/tmp/opencode/wp0.md`

> **What is actually proven:** see §10. Nothing was compiled (CI is the only compile gate — no local
> Android SDK) and nothing was executed on a device. Do not read §1–§9 as "verified fixed on device".

---

## 1. Prompt root cause

Source of truth: `PromptController.java`.

- `handleEvent` does `res = getResponse(...)`; `if (res == null) callback.sendSuccess(null)` else
  `res.accept(v -> v.dispatch(cb), …)`.
  **The returned `GeckoResult` must be completed exactly once.** Returning `null` is a semantic
  dismiss but leaves the prompt in `mStorage` forever (leak).
- `PromptStorage.dismiss(id)` — driven by `GeckoView:Prompt:Dismiss`, i.e. the page navigated away —
  calls `delegate.onPromptDismiss(prompt)` then removes it, **but does not set `mIsCompleted`**. So
  after Gecko withdraws a prompt, `prompt.isComplete()` is still `false` and one `prompt.dismiss()`
  is still legal.
- `BasePrompt.complete()` is `private` and reached only from `confirm()` / `dismiss()`, both of which
  `throw new RuntimeException("Cannot confirm/dismiss a Prompt twice.")`. **That throw was the crash.**
- `PromptResponse`'s constructor is package-private, so `confirm()`/`dismiss()` are the only ways to
  produce one.

**The bug:** the dialog and Gecko each had an independent, legal reason to resolve the same prompt
(user tap *and* page navigation away) and nothing owned the transition.

**The fix** (`engine/BrowserPromptDelegate.kt`): a private nested `Resolution(prompt, result)` with an
`UNRESOLVED → ANSWERED` transition, `answered = true` set *before* producing the value,
`prompt.isComplete` as a secondary guard (logged via `AppLog.w`, **no try/catch**).
`show(prompt, build)` now takes `build: (Context, (() -> PromptResponse) -> Unit) -> AlertDialog`, so
every call site passes `finish { prompt.dismiss() }` / `finish { prompt.confirm(…) }` and **nothing is
completed before the guard is checked**. `answerNow(prompt) { … }` covers all no-host / no-Activity
paths plus `onPopupPrompt` / `onSharePrompt`. `onDateTimePrompt` and `onFilePrompt` route through
`Resolution` with `attachWithdrawGuard(dialog)`. `onPromptDismiss` answers **first**, then hides the
dialog. No other `.kt` file touches `BasePrompt`.

---

## 2. Exact cookies / permission problem

Source of truth: `assets/omni.ja`.

- `GeckoSessionSettings.Builder.contextId` → `StorageController.createSafeSessionContextId` →
  `"gvctx"+hex`, exposed as browser attribute `geckoViewSessionContextId` → **origin attribute
  `geckoViewSessionContextId`** on the content principal, i.e. on every cookie / localStorage /
  permission of that workspace session (`chrome/geckoview/content/geckoview.js:442`).
- `ext-toolkit.js`: cookie store ids are **only** `firefox-default`, `firefox-private`,
  `firefox-container-<n>`. Android's `getContainerForCookieStoreId` is `parseInt` only
  ("TODO: Bug 1643740, support ContextualIdentityService on Android").
- `ext-cookies.js` `oaFromDetails` builds the filter OA as
  `{userContextId, privateBrowsingId, firstPartyDomain, partitionKey}` — **it never includes
  `geckoViewSessionContextId`** — and `convertCookie` labels a session-partitioned cookie
  `firefox-default`.
- `getCookiesFromHost(host, oa)` is an **exact OA match** (absent keys = default). Proven two ways:
  marionette `cookie.sys.mjs` calls it with `{}` and again with `{partitionKey}` and *concatenates*
  the results; devtools `storage/cookies.js` enumerates an OA list "so we can get cookies from all
  jars".

**Consequence:** in a workspace tab, `cookies.getAll()` cannot see that session's jar and
`cookies.set()` writes into the **default** jar (silently no effect on the page).
**Permission storage, by contrast, *is* context-aware** — `GeckoViewStorageController`
`GetPermissionsByURI` / `SetPermissionByURI` take `contextId` and build the principal with
`{geckoViewSessionContextId, privateBrowsingId}`, and `GetAllPermissions` returns `contextId` per
permission — so cookie access and permission access are asymmetric.

**Not fixable from the app:** `ext-cookies.js` lives in signed `omni.ja`, and dropping `contextId`
would destroy session isolation. Documented in `docs/EXTENSIONS.md`, replacing the earlier over-claim.

**What *was* fixable and was fixed:**

- Install / update / optional-permission prompts arriving with `host == null` were silently
  auto-denied. They now log
  `AppLog.w(TAG, "Install/Update/Optional-permission prompt auto-denied: no host (id=…)")` while
  keeping the existing deny values.
- `logGrants(stage, ext, permissions?, origins?, dataCollection?)` — EXTDBG-gated, **names only**,
  never values or secrets — runs at `wire()` and from all three prompt handlers.
- `ExtensionsActivity.showDetails` now also shows `requiredOrigins` and the union of
  `grantedOptionalPermissions` / `grantedOptionalOrigins`. Previously a user could not see *why* an
  add-on wanted access.

---

## 3. APIs improved / audited

Read directly out of the extracted `omni.ja`, not from documentation.

**Present:** activityLog, alarms, browserAction, browserSettings, browsingData, clipboard,
contentScripts, cookies, declarativeNetRequest, dns, downloads, extension, i18n, idle, management,
networkStatus, notifications, pageAction, permissions, privacy, protocolHandlers, proxy, runtime,
scripting, storage, tabs, theme, userScripts, webNavigation, webRequest
(+ `ext-android.js` / `ext-c-android.js`).

**Absent entirely** (no module, no schema): bookmarks, commands, contextMenus/menus, devtools, find,
fullscreen, geolocation, history, omnibox, search, sessions, sidebarAction, topSites, windows.

- `runtime.getBrowserInfo` **exists** (`ext-runtime.js:335`).
- `WebExtension.MetaData` grant arrays exist: `requiredPermissions`, `requiredOrigins`,
  `grantedOptionalPermissions`, `grantedOptionalOrigins`, `optionalDataCollectionPermissions`,
  `grantedOptionalDataCollectionPermissions`.
- `WebExtensionController.addOptionalPermissions` / `removeOptionalPermissions` /
  `onOptionalPermissionsChanged` all exist.

**Verified unchanged and intact:**

- popup height **420dp** (`sheet_extension_popup.xml`)
- install still accepts `http://` (`ExtensionManager.kt:177`)
- `EXTDBG = false`
- `devtools` appears only in a doc comment
- `AppDownloadManager.kt` **zero-diff**
- Room still `version = 5`
- **no WebView class anywhere** (`android.webkit.MimeTypeMap` / `URLUtil` only)
- **no new try/catch in the diff**

---

## 4. Remaining GeckoView limitations

1. **Cookie-store partitioning cannot be addressed by `browser.cookies`** (§2). The polarity question —
   does `getAll()` from the default context also *leak* other sessions' cookies? — is **still
   unverified**.
2. **No `devtools`, `contextMenus`, `commands`, `bookmarks`, `history`, `find`, `sidebarAction`** —
   Gecko rejects these as "not defined"; there is no app-side workaround that is not a fake.
3. **No page-zoom API at all.** The only knobs are `GeckoRuntimeSettings.setFontSizeFactor`
   (runtime-wide, text-only — ignores images, breaks layout) and read-only zoom state.
4. **Optional-permission native surface:** `addOptionalPermissions` / `removeOptionalPermissions` were
   deliberately **not** called — `browser.permissions.request()` already arrives on `onOptionalPrompt`;
   a native grant surface would be a second permission-decision path with no on-device evidence Gecko
   needs it. *(Open decision — §11.)*
5. **`javascript:` callback shape is inferred, not observed.** The design in §5 is loop-safe under
   *either* possible behaviour (Gecko reports the script, or reports the page it ran against, or gives
   no progress callbacks at all), but which one actually happens is a device-test item (§12).

---

## 5. Page scale — implementation & storage

**Mechanism:** CSS `zoom` on `document.documentElement`, applied through the existing `javascript:`
in-page-script channel (the proven bookmarklet loader). `script(percent)` emits one line that reads
`parseFloat(d.style.zoom)||1`, **early-returns when `old === n`** (an unchanged scale costs nothing),
sets `d.style.zoom = n` and re-scales `window.scrollX/Y` by `n/o` **in the same script** — scroll
position preserved, no reload, no history entry. `zoom` rather than `transform: scale` because it
keeps real layout (media queries, reflow, hit-testing).

**New tagged slot, separate from bookmarklets:** `Tab.internalScript` + one-shot `Tab.internalLoad` +
`Tab.appliedScale: Int?`. `TabManager.runScript` was refactored into
`private fun loadScript(tab, source, internal)` with `runScript(...)` = `internal = false` and
`runInternalScript(...)` = `internal = true`. A `gs.load()` failure clears whichever slot it set —
a stale slot would authorize whatever page-initiated `javascript:` arrives next.

**Loop guard (the critical property):**

- `onLoadRequest` clears **both** markers for any non-`javascript` scheme, *before* that navigation's
  first callback.
- The `javascript:` branch matches **two independent slots**: `pendingScript` (user bookmarklet) and
  `internalScript` (scale change), so neither can consume the other. Every page-initiated `javascript:`
  finds both empty and stays denied, exactly as before. If only the bookmarklet matched,
  `internalLoad` is released too.
- `onPageStart`, `onProgressChange`, `onPageStop`, `onLoadError` and `onLocationChange` swallow
  everything while `internalLoad` is set.
- `onPageStart` keys on `internalLoad` and **not** on the reported URL: a `javascript:` load may
  report either the script or the page it ran against, and keying on the URL would let the script's own
  stop look like a fresh document and re-apply for ever.
- `onPageStop` returns immediately when `internalLoad` — **it must not re-enter `PageScale`**, which is
  exactly what issued that load.
- `onPageStart` never assigns a `javascript:` URL to `tab.url` (belt-and-braces alongside the
  `internalLoad` guard), and resets `appliedScale = null` because a fresh document has no CSS zoom.

**Skipped documents** (`PageScale.isScalable`): `about:`, `resource://`, `moz-extension://`,
`view-source:`, `chrome:`, `javascript:`, the start page, and any tab with `error != null`.

**Apply points:**

| Trigger | Path |
|---|---|
| page finished loading | end of `TabDelegates.onPageStop` |
| user picks a percent | `showPageScaleDialog` → `PageScale.setPerSite` → `applyFor` |
| user resets the site | `clearPerSite` (writes `ASK`) → `applyFor` |
| global default changed | `SettingsFragment.onSharedPreferenceChanged` → `PageScale.applyDisplayed` |
| tab becomes displayed | `TabManager.setDisplayed` (when `changed`) — restores / re-hibernated tabs |

`applyFor` **refuses while `tab.isLoading`**, so it can never race a real page load's start callback;
that load's own `onPageStop` applies it instead. It also no-ops when `appliedScale == want`, so an
unchanged scale issues **no load at all**.

**Guard against a half-applied script:** if Gecko were to swallow the script load entirely (no
callbacks), `internalLoad` stays set until the next real navigation's `onLoadRequest` clears it —
bounded damage (one SPA location update may be suppressed), never a loop. See §12 item 3.

---

## 6. Global vs per-site

| | Global default | Per-site rule |
|---|---|---|
| Where | Settings → *Page scale* (`ListPreference`) | App menu → *Page scale* |
| Storage | SharedPreferences, `Prefs.KEY_PAGE_SCALE` (`"page_scale"`, Int, default 100) | existing `site_permissions` table, new `SitePermissionType.PAGE_SCALE` (`"page_scale"`, `geckoType = null`, `managed = false`), keyed `(sessionId, origin)`; value **is** the percent 50…200 |
| Reset | change the setting | "Use the default for this site" writes `ASK`, which `SitePermissionStore.set` treats as a **delete** — no Room migration |
| Scope | every tab, every session | that origin, in that browser session |
| Shown as | the dialog message text ("The global default (%1$d%%) is set in Settings") | the checked radio row |

Both are 50…200 in 10% steps: `PageScale.PERCENTS` (`MIN_PERCENT=50`, `MAX_PERCENT=200`, `STEP=10`,
`DEFAULT_PERCENT=100`) = `arrays.xml` `page_scale_values` (16 entries).
`ASK`/`ALLOW`/`BLOCK` are 1/2/3 — outside 50…200 — so "no rule" is detected with no schema flag.
`managed = false` keeps it out of the Site permissions dialog.

---

## 7. Pull-to-refresh changes

**Not disabled** — three independent gates were added so only a deliberate pull can fire
(`ui/browser/PullRefreshFrameLayout.kt`):

1. **Trigger area** — the finger must start in the top band of the container: top 40% of its height,
   floor 96dp (`max(96dp, height * 0.40)`). A pull started lower belongs to the page.
2. **Axis lock** — the direction is decided **once**, after **48dp** of travel (far above touch slop,
   so jitter never locks a direction), from *relative* travel: `|dy| > |dx| * 1.5` → VERTICAL,
   otherwise HORIZONTAL. Only VERTICAL may intercept, and the decision is final for the whole gesture
   (no flip-flopping mid-drag).
3. **Threshold raised** — `refreshDistance` 96dp → **160dp**, so an inertial fling that happens to
   start at the top of the page cannot reach it. The spinner fill uses the new distance, so the
   threshold stays visible instead of guessed.

Plus:

- `canRefresh()` is **re-checked at interception time and again on release**.
- `ACTION_CANCEL` always aborts — it can never fire a refresh off a gesture the system took away.
- `ACTION_UP` / `ACTION_CANCEL` reset `armed` / `pulling` / `axis`.
- The class only ever **declines to intercept**; it never consumes without being armed.

---

## 8. Sidebar changes

New `GestureDrawerLayout : DrawerLayout` (`ui/browser/GestureDrawerLayout.kt`) is now the root of
`res/layout/activity_browser.xml`, replacing the fully-qualified `androidx.drawerlayout.widget.DrawerLayout`
tag. `BrowserActivity.drawerLayout` stays typed `DrawerLayout`, so `findViewById`, the lock mode and
both drawer listeners are untouched.

Axis arbitration: `UNDECIDED → HORIZONTAL | VERTICAL`, decided once after `2 × scaledTouchSlop` of
movement.

- **VERTICAL → never calls `super.onInterceptTouchEvent` at all**, so a vertical drag is never offered
  to DrawerLayout's drag helpers and stays with the content (page scroll, list scroll inside a drawer,
  nested scroll containers).
- **HORIZONTAL / UNDECIDED → passed straight through**, so swiping the drawer open and closed works
  exactly as before (nothing here can make the drawer un-swipeable — it only removes a *vertical*
  parent interception).
- `ACTION_UP` / `ACTION_CANCEL` are **always forwarded to `super` first**, so `ViewDragHelper` is never
  left mid-drag, and then the decision resets for the next gesture.

This is *declining to intercept*, not consuming: **no second gesture recognizer**, no synthetic
events, no change to taps, long-press or scrolling.

**Note:** `BrowserActivity` still calls `setDrawerLockMode(LOCK_MODE_LOCKED_CLOSED)` (only
`openDrawer()` unlocks; `onDrawerClosed` re-locks). That pre-existing design — "drawers are opened only
through their buttons (a swipe from the edge would fight with page gestures)" — was **deliberately left
alone**; see §11.

---

## 9. Files changed + why

| File | Why |
|---|---|
| `engine/BrowserPromptDelegate.kt` | WP1 — `Resolution` single-transition guard, lambda `finish`, `answerNow`, withdraw guards |
| `engine/TabDelegates.kt` | WP5 — two-slot `javascript:` gate, `internalLoad` suppression in all five callbacks, `appliedScale` reset, apply on `onPageStop`, `javascript:` never becomes `tab.url` |
| `tabs/Tab.kt` | WP5 — `internalScript`, `internalLoad`, `appliedScale` (each with its "why a separate slot" doc) |
| `tabs/TabManager.kt` | WP5 — `loadScript` / `runInternalScript` split + slot rollback on `gs.load()` failure; apply on `setDisplayed` |
| `engine/PageScale.kt` **(new)** | WP5 — percent resolution, in-page script, apply/clear, all guards |
| `permissions/SitePermissions.kt` | WP5 — `PAGE_SCALE` rule type |
| `core/Prefs.kt` | WP5 — `KEY_PAGE_SCALE` + `pageScaleDefault` |
| `res/xml/preferences.xml` | WP5 — global `ListPreference` next to the existing pinch-zoom switch |
| `res/values/arrays.xml` | WP5 — `page_scale_entries` / `page_scale_values` (50…200 / 10) |
| `res/drawable/ic_zoom.xml` **(new)** | WP5 — app-menu icon (Material `zoom_in`) |
| `ui/settings/SettingsActivity.kt` | WP5 — re-apply the on-screen tab when the global default changes |
| `ui/browser/BrowserActivity.kt` | WP2 — `onExtensionNewTab` now attributes to `displayedTab → activeId`; WP5 — `ic_zoom` menu entry next to `ic_desktop` + `showPageScaleDialog` |
| `extensions/ExtensionManager.kt` | WP2 — `logGrants` (EXTDBG-gated, names only) + explicit `host == null` logging |
| `ui/extensions/ExtensionsActivity.kt` | WP2 — surface `requiredOrigins` + granted optional access |
| `docs/EXTENSIONS.md` | WP4 — cookie claim corrected to the `geckoViewSessionContextId` explanation, GV155 API matrix, two new "Works" rows |
| `ui/browser/PullRefreshFrameLayout.kt` | WP6 — trigger area, axis lock, 160dp threshold, re-checks, cancel |
| `ui/browser/GestureDrawerLayout.kt` **(new)** | WP7 — axis arbitration |
| `res/layout/activity_browser.xml` | WP7 — root swap |
| `res/values/strings_v219.xml` **(new)** | all new strings (one shard per release, per convention) |

---

## 10. Validation — what is actually proven

| Check | Result |
|---|---|
| `python3` XML parse (all `res/**/*.xml` + `AndroidManifest.xml`) | **109 / 109 well-formed**, incl. `strings_v219.xml`, `ic_zoom.xml`, `activity_browser.xml` |
| Resource audit | **749 declared / 709 referenced / 0 missing** (incl. `@string/page_scale*`, `@array/page_scale_values`, `R.drawable.ic_zoom`) |
| `ktbal.py` | **all 57 `.kt` files balanced** (incl. the 2 new untracked files) |
| `git grep -nI "ghp_[A-Za-z0-9]"` | clean (also clean for `AKIA…` and `-----BEGIN`) |
| Room schema version | `5` (unchanged) |
| WebView class references | **0** |
| `AppDownloadManager.kt` | zero-diff |
| new `try`/`catch` in the diff | **0** |
| `EXTDBG` | `false` |

Library-provided styles (`Theme.*`, `Widget.*`, `PreferenceThemeOverlay`) and one `R.string.x`
appearing inside a comment are excluded from the resource audit as known false positives.

**Not proven, and not claimed:** nothing was compiled and nothing was executed. **CI is the only
compile gate** (no local Android SDK on this host), and no behaviour was exercised on a device.

---

## 11. Decisions needing approval

1. **Drawer lock mode.** `LOCK_MODE_LOCKED_CLOSED` was **not** removed, because "drawers open only
   through their buttons" is an existing deliberate decision with a written rationale. If edge-swipe
   *should* open a drawer (which "don't make the drawer un-swipeable" could be read to demand), that
   is a UX change — say so and I'll unlock it alongside the new arbitration.
2. **Optional-permission native surface.** `addOptionalPermissions` / `removeOptionalPermissions` are
   still not called. Adding them creates a second permission-decision path — approve before I touch it.
3. **`EXTDBG` tension.** Its doc comment says the switch is "removed in 2.1.9", yet this run *added*
   `EXTDBG` call sites (`logGrants`, the three prompt handlers). Either the comment or the call sites
   should give; both were left as-is. (`EXTDBG = false`, so nothing is logged today.)
4. **Menu icon.** `ic_zoom` is the stock Material `zoom_in` glyph; swap it if you want something else.
5. **Docs for the three UI features.** `README` / `ARCHITECTURE` / `TEST_PLAN` were not updated for
   page scale / pull-to-refresh / gestures (out of scope this run). A small follow-up is recommended.

---

## 12. Device test list

### Carried over from `BUGFIX_218_W.md` (still unrun)

- Tampermonkey / Cookie-Editor install result + `InstallException.code`
- `runtime.getBrowserInfo`
- `browser.notifications`, `contextualIdentities`, `proxy`, `commands`, `contextMenus`
- whether `downloads.download()` fires
- popup toggle / tab-switch / Background behaviour
- `tabs.query({active:true})`
- `.xpi` toast with `host == null`
- **cookie-store polarity** — does `cookies.getAll()` from the default context also return other
  sessions' cookies?

### New this run

1. **Prompts:** open an auth prompt, navigate away mid-dialog → no crash and the dialog disappears;
   confirm/deny after a withdrawal → no `RuntimeException`; file-chooser and date/time prompts;
   HTTP-auth prompt with no host.
2. **Page scale:** apply 130% → no reload, scroll preserved, **progress bar does not flash**; apply
   while idle / while loading (must defer) / on an extension page (must skip); reload → scale
   reapplied; close & reopen the tab → restored; per-site rule survives a session switch; "Use the
   default" then change the global in Settings → the site follows the new default.
3. **Loop guard:** on a heavy SPA, change scale 3× quickly — exactly one in-page load per change, no
   repeated zooming, and the address bar keeps updating on `pushState` afterwards.
4. **Slot isolation:** run a bookmarklet immediately after a scale change — both must execute,
   independently of each other.
5. **Pull-to-refresh:** at scroll top, pull from the top band → refresh at ~160dp; pull from
   mid-screen → nothing; horizontal swipe at the top → nothing; diagonal → nothing; fast fling down
   from the top → no refresh; cancel mid-pull → no refresh; pull up past the top → nothing.
6. **Drawers:** vertical drag near the edge (drawer open *and* closed) → page scrolls, drawer does not
   move; horizontal drag → drawer opens/closes normally; taps, long-press, tab-grid swipes and nested
   lists inside the drawers all unaffected.
7. **Regression sweep:** session/tab isolation, downloads, tab groups, workspaces, JS URL execution,
   extension install, desktop/mobile mode (must re-apply together with the scale), fullscreen exit
   button, bookmarklets.
