# Plan 2 — v2.1.10 diagnostics, page-scale & performance (2.1.10 run)

Companion to `BUGFIX_2110_A9.md` (Plan 1). Everything below was fixed in the same
release train; root causes are source-proven against the pinned GeckoView
`155.0.20260903215306` sources and the stock `omni.ja` unless marked as a
hypothesis.

## 1. Page scale did nothing on some sites (CSP) — FIXED, needs device verification

### Root cause
GeckoView 155 exposes **no page-zoom API to Java** (verified in
`GeckoSessionSettings` / `GeckoRuntimeSettings` / `GeckoSession`: the only knobs
are the runtime-wide, text-only `fontSizeFactor` and a read-only compositor
`mViewportZoom`). The app therefore applied scale as **CSS `zoom` via an
in-page `javascript:` load**. An in-page load is subject to the page's
Content-Security-Policy: Discord's `script-src` does not allow `'unsafe-inline'`,
so the load was denied and the zoom silently did nothing — the reported
"scale does nothing on some sites" symptom. Stock Chrome/Firefox never hit this
because they use native per-tab zoom.

### Fix (commit `a428dd3`)
Native zoom, one new hunk in the already-patched `assets/omni.ja`:

| piece | what it does |
|---|---|
| `tools/patch_omni_cookies.py` | 5th hunk in `modules/GeckoViewNavigation.sys.mjs` + `PATCH_MARKERS` entry; asset regenerated & CI-gated exactly like the cookie patch |
| `GeckoView:LoadUri` intercept | app-issued `moz-scale:<percent>` URIs write `browsingContext.fullZoom = percent/100` instead of navigating — **never enters the content process**, so no CSP, no history entry, no load callbacks |
| `TabManager.setPageScale` | `Loader().uri("moz-scale:N").flags(LOAD_FLAGS_BYPASS_LOAD_URI_DELEGATE)` — the Java delegate is skipped by design (source-proven in `GeckoSession.shouldLoadUri`) |
| `TabDelegates.onLoadRequest` | any `moz-scale:` that *does* reach the delegate is page-initiated (a link) and is denied |
| `PageScale.applyFor` | re-issues whenever `appliedScale` is null (fresh/restored document — the engine's zoom **persists across documents**, unlike CSS) or differs; pushes 100% onto start/error/privileged pages |
| removed | `internalScript` / `internalLoad` / `runInternalScript` — the whole in-page script-slot machinery existed only for the old CSS zoom; the `pendingScript` bookmarklet path is untouched |

### Device checks
1. Set 130% on a normal site → no reload, scroll preserved, progress bar does not flash.
2. Set 130% **on Discord** → now takes effect (this was the failing case).
3. Reload → re-applied; switch tabs away/back → re-applied; per-site rule survives a session switch.
4. Navigate from a 130% site to another origin with no rule → back to 100% (engine zoom persists, `applyFor` resets it).
5. Home (start page) from a 130% site → start page at 100%.
6. Bookmarklet still executes (it shares the `javascript:` gate the scale used to use).

## 2. Discord quest claim works in stock Firefox — config diff & capture path

Your device result: quest claim **succeeds in Firefox for Android** (and Chrome),
fails in this build → per the decision tree this is **our Gecko configuration**,
not site-side. The structurally identical config diff vs stock Firefox
(`contextId` partitioning is the biggest difference) is documented in the Plan-2
research notes; no diff alone is proven to be the cause yet.

**Capture step (new in this build):** Settings → *Diagnostics* →
**"Web console to logcat"** (commit `34aceaa`; always on in debug builds).
Reproduce the claim failure with it on, then:

```
adb logcat -c
# reproduce the failing claim in the app
adb logcat | grep -iE "GeckoConsole|console|CSP|contentblocking"
```

Post the output — CSP violations, extension errors or JS exceptions during the
claim will name the exact policy that fires. `GECKO_TRACKING_TYPE → VALUE_DENY`
(`BrowserActivity`) and the ETP/cookie settings are the current suspects to
re-test once the console output identifies what is being blocked.

## 3. Performance pass (commit `7cd5f2d`)

Done (each verified by file:line during the audit):

| hotspot | fix |
|---|---|
| `setDisplayed` pushed `setActive`+`setFocused`+`setTabActive` to **every** live session per switch | skip tabs already in state (`Tab.nativeActiveState`, reset on session create/reopen/drop) |
| download progress: permission + channel binder calls billed on every ~300 ms tick even when the update would be throttled | stall check moved **before** `canPost`/`ensureChannels`; channel existence probed once per process |
| SPA title changes serialised the whole `SessionState` JSON and upserted the row | `TabDao.updateTitle` (own column) via `persistTitle` |
| `IncognitoNotifier.update` cancelled/notified on every active-session emission | `postedForId` guard |
| session chip rebuilt tinted dot + `findViewById` on every emission | id+name+color guard (`chipSession`) |
| `setImageResource` on both toolbar icons every progress tick | predicate-change guards |
| cold start / switch cost invisible in release logs | `Perf` tag logs: `Application.onCreate … ms`, `first window focus … ms after process start` (process start → first interactive frame), `setDisplayed took … ms` when ≥ 16 ms |

Still open (identified, not changed this run — candidate next pass):
eager constructor I/O (`BrowserCore`, `LocalContentLoader`, `ProjectManager`,
`Prefs`), first tab gated on DB restore, `GeckoRuntime.create` on the main
thread (already posted to the handler by `warmUp()`), DownloadsActivity
`notifyDataSetChanged` per tick, TabsSheet full-rebuild filtering,
`updateTabCount` O(n), ProjectsActivity tree walk.

## 4. A9-5 follow-up
User result on the first fix: *"fixed but stuck sometimes, sometimes it never
does it"*. Root cause and fix are in commit `86b8b32`
(drag state must survive adapter notifications; visual state must not):
`dragPendingRebuild` deferral, ACTION_DOWN stale-drag abort, `clearView`
dispatch from the **model** row with both holders unstuck. This commit was part
of the Plan-2 push — please re-run the A9-5 checks against the build that
contains it.
