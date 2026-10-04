# BUGFIX_2110_A9.md — v2.1.10 (versionCode 18)

Scope: the five A9/A4 regressions from the v2.1.9 deep-audit checklist, shipped as **one** release.
Every item names the **root cause**, not the symptom. No new try/catch anywhere, no Gecko/Material
API used before verifying it against its own sources, and the v2.1.9 behaviour that passed
checklist stays untouched.

The release's headline defect: **pull-to-refresh fired while an inner scroller (Discord's chat
list) was still scrolled up**, and the tabs grid could not group or move groups the way Chrome
does.

---

## A9‑1 — Pull-to-refresh triggers inside inner scrollers (Discord chat)

### Root cause

`PullRefreshFrameLayout.canArm` / `canRefresh` decided "at top?" purely from the **root**
`scrollY <= 0`. Discord renders its chat as an inner scroll container; the root document never
moves, so it is permanently "at top" and a drag anywhere — mid-chat — armed a refresh. The
information needed to decide correctly (what is actually scrollable under the finger, and whether
it is still scrolled toward its top edge) lives inside Gecko's APZ, and the app never asked for it.

### Fix (source-proven, GV155)

GeckoView exposes exactly one query for this: `GeckoView.onTouchEventForDetailResult(MotionEvent)`
(javadoc: call it for **ACTION_DOWN only**) → `PanZoomController.InputResultDetail`, whose
state is the same `InputResultDetail` the android-components
`canOverscrollTop()` helper is built on.

* `BrowserActivity` (`webTouchListener`, :556) issues **one request per gesture on ACTION_DOWN**
  (`ptrGesture` counter tags the result, `ptrDetailGesture` / `ptrDetail` store it,
  `ptrDetailWaitMs = 150L` bounds the wait) and returns `true` for DOWN while a session exists, so
  Gecko always sees the full stream.
* `canRefresh` (:366) now consults the detail first:
  * detail resolved → `pullAllowed(d)` (:595): `HANDLED` (nothing scrollable at the point) →
    refresh allowed only when the point cannot overscroll toward the top
    (TOP flag clear + `OVERSCROLL_FLAG_VERTICAL` set — flag polarity verified in native
    `Axis.cpp`: **flag set = can still scroll toward that edge**, so at-top ⇔ `SCROLLABLE_FLAG_TOP`
    **clear**); `HANDLED_CONTENT` (a scroller consumed it) → **no refresh**;
    undecidable (`INPUT_RESULT_UNHANDLED/IGNORED`) → legacy rule;
  * detail not yet resolved within 150 ms → no refresh (wait, never guess);
  * no detail at all (start page, legacy path) → the old `scrollY <= 0` rule.
* `canArm` (DOWN gate) keeps the cheap legacy root-scroll rules — arming never waits on Gecko.
* Decision logged under the `PTR` tag for on-device diagnosis.

Arming semantics, non-inner cases, and the horizontal/double-scroll guards are unchanged: same
arm rules, only the *release* decision learned to ask Gecko.
---
Result:
yes it is now fixed and good.
--
## A9‑2 — Gray check circle on selected tab cards (A4)

### Root cause

`item_tab.xml` tried to hide the selection tick with `app:checkedIconVisible="false"` on the
`MaterialCheckBox`. That attribute exists **only on `Chip`** — it is not a `MaterialCheckBox` /
`MaterialCardView` attribute at all (verified in Material Components source), so it was silently
inflated as a no-op and `MaterialCardViewHelper` kept drawing its default dark-gray
`checkedIcon` whenever `card.isChecked` was set.

### Fix

`app:checkedIcon="@null"` on the card (`item_tab.xml:17`). `MaterialCardViewHelper.setCheckedIcon`
explicitly maps `null` to `CHECKED_ICON_NONE`, so no icon is drawn in any state. The XML comment
sits **above the element** (XML attributes are not allowed to carry comments). No other layout in
`res/` referenced `checkedIconVisible`.
---
Result:
fixed
--
## A9‑3 — Extension popup drags its own sheet while touching content

### Root cause

The popup is a `BottomSheetDialog` with the default `BottomSheetBehavior`, i.e.
`isDraggable = true`. Any touch that starts inside the popup's content (the action buttons, the
input field) could grab the sheet and drag it down — the popup could not be used reliably.

### Fix

`BrowserActivity.showExtensionPopup` (:1381) sets `sheet.behavior.isDraggable = false`, and
`enablePopupTitleDrag(sheet, popupTitle)` (:1399) restores exactly one drag affordance: a
**translate gesture on the title bar** (the standard grab handle), with the usual 120 dp
dismiss threshold and a 150 ms snap-back. Content touches never move the sheet anymore.
---
Result:
fixed
--
## A9‑4 — Download notification actions

### Root cause

Three gaps in the v2.1.7 notification flow:

* a **running** download offered no Pause — the only way to stop a transfer without killing it
  was to open the app;
* a **paused** download offered only Resume — it could not be discarded (Cancelled) from the
  notification, so it lingered;
* the **completed** result notification had no real actions: no "Open file" button (only the
  content tap) and no explicit Close, and the receiver understood only Cancel/Resume.

### Fix

* `DownloadNotifier.kt`: new `ACTION_PAUSE` / `ACTION_DISMISS` constants (:44‑45) and
  `RC_OPEN_FILE` (:52). `active()` (:93): RUNNING → **Pause + Cancel**, paused → **Resume +
  Cancel**, PENDING → Cancel only (nothing exists to pause yet). `finished()` (:126): on success →
  **Open + Close**; "Open" is an *activity* PendingIntent straight to
  `BrowserApp.core().downloads.openIntent(d)` (skipped when the file is gone) — no receiver hop,
  no new try/catch; Close dismisses through the existing auto-cancel path.
* `DownloadActionReceiver.kt` (:30‑31): `ACTION_PAUSE → core.downloads.pause(id)`,
  `ACTION_DISMISS → NotificationManagerCompat.cancel(id.hashCode())`. "Open" is deliberately
  *not* in the receiver (it is an activity intent; explicit intents only — the receiver is
  `exported=false` with no intent-filter).
* Strings: `dl_close` in the new `values/strings_v2110.xml` (existing
  `dl_pause/dl_resume/dl_cancel/dl_open` reused; drawables `ic_pause/ic_play/ic_close/
  ic_open_in_new` exist).
---
Result:
Fixed
--
## A9‑5 — Tabs grid: Chrome-style drop-to-group and group-header drag

### Root cause

Grouping in the grid required a **600 ms finger hold** (`HOLD_GROUP_MS`), and the hold timer's
cleanup cleared the drop targets *before* `ItemTouchHelper.clearView` read them — so on release
the targets were already gone and grouping never fired. There was also no way to reorder group
headings at all, and the grid reflowed live mid-drag (`onMove` real moves), which made both
problems worse to hit.

### Fix (`TabsSheet.kt`, reworked drag section :231‑512)

* **Finger tracking**: a plain `OnTouchListener` on the RecyclerView (`dragFingerListener` :318)
  records the coordinates on MOVE/UP/CANCEL and recomputes the highlight (`updateDropTarget`
  :278); it always returns `false`, so RecyclerView and ItemTouchHelper see every event exactly
  as before. UP/CANCEL deliberately do **not** clear the targets: `clearView` (:479) runs
  immediately after our listener and reads them — the old hold-timer cleared them first, which is
  why grouping never fired.
* **Drop dispatch** (in `clearView`, captured before state reset): card over card →
  `groupByDrag` (:338) groups **immediately** — target's group wins, else dragged tab's group,
  else a new group (same default name/colour as the group dialog); card over a GROUP header →
  `dropCardOnHeader` (:397) lands it as the group's first member; no target →
  `dropCardReorder` (:375), nearest laid-out card, nearer edge decides before/after.
  Pinned tabs and same-group drops fall through to plain reorder (checked at highlight time).
* **GROUP headers now drag**: `getMovementFlags` gives them UP|DOWN only (PINNED/OTHER stay
  fixed anchors), and `dropHeader` (:410) moves the whole block (header + its cards) before the
  nearest group header, clamped to the GROUP region, no-op on its own span; order committed
  through the new `TabManager.applyGroupOrder` (:176 — validates the id list against
  `groupsFor(sessionId)`, rewrites positions, persists + notifies like `moveGroup`).
* **No mid-drag reflow**: `onMove` returns `false`; the dragged view just follows the finger.
  Drop-side section rules are preserved in `moveRowTo` (:356): a card may never enter the pinned
  section it does not belong to, and never above the first header. Committed through the existing
  `TabManager.applyOrder` / `normalizeOrder` / `persistSession` chain.
* **Release in place = no-op**: the dragged view keeps its layout slot (only the draw is offset),
  so a finger still inside the original bounds skips the drop — a long-press without movement
  cannot shift neighbours.
* Swipe-to-close (v2.1.7, issue O) unchanged; its recovery clears bail out of `clearView`
  (`dragHolder !== vh`), and `onSwiped` cleans state and closes the tab as before.
* Card-stroke highlight is restored through `refreshTab` (a plain `strokeWidth = 0` would erase
  the current-tab stroke `bind()` applies). Header highlight = alpha 0.55.
---
Result:
fixed but stuck sometimes, sometimes it never does it
--
---

## Validation performed

* **CI is the only compile gate** (no local Android SDK / gradle in this environment). Every
  commit above must be green (`assembleDebug` + `assembleRelease`) before the tag.
* Delimiter balance — brace/paren deltas for every touched `.kt` file compared against
  `git show HEAD:<file>` with block comments/strings stripped: all **(0, 0)**.
* XML — `item_tab.xml`, `strings_v2110.xml` parse with `xml.etree.ElementTree`.
* Stale-reference greps — `HOLD_GROUP_MS`, `holdGroupRunnable`, `resetHoldTimer`,
  `cancelHoldTimer`, `evaluateGroupTarget`, `fingerSeen`, `clearGroupTarget`, `adapter.dirty`,
  `adapter.move`, `showPause`: all clean. No `checkedIconVisible` left in `res/`.
* Resource existence — `group_default_name`, `group_created`, `dl_*` strings and
  `ic_pause`/`ic_play`/`ic_close`/`ic_open_in_new` drawables all resolve.
* Gecko/Material facts read from source, not from memory: GV155
  `GeckoView.onTouchEventForDetailResult`, `PanZoomController.InputResultDetail` (and
  android-components `canOverscrollTop()`), native `Axis.cpp` flag polarity, Material
  `MaterialCardViewHelper.setCheckedIcon(null)` / `checkedIconVisible` = Chip-only.

## On-device checklist — *pending, must be confirmed before calling the behaviour fixed*

1. **PTR, Discord**: open a long server channel, scroll mid-chat, drag down → **no** refresh arm;
   scroll the chat back to its very top and drag → refresh arms and fires. Repeat over
   Discord's member list / settings screens (inner scrollers everywhere). Logcat filter `PTR`
   should show the decision.
2. **PTR, legacy**: on `example.com` (no inner scroller) drag down at top → refresh; mid-page →
   nothing; after a fast fling settles at top → refresh. A PTR started at top must still work
   when the page keeps scrolling (`HANDLED` path).
3. **PTR latency**: pull quickly right after load — nothing should flash; release before 150 ms
   with the detail unresolved = no refresh (deliberate).
4. **Tab card tick**: select a tab in the grid (selection mode) → check icon is **invisible** on
   the card; the card tint/fill still shows selection.
5. **Extension popup**: open an add-on popup, tap/drag inside its content → sheet never moves;
   drag the title bar down > 120 dp → sheet dismisses; short title drag → snaps back.
6. **Download notifications**:
   * start a large download → notification shows **Pause + Cancel**; Pause → progress stops,
     notification becomes paused with **Resume + Cancel**; Cancel from there removes it.
   * pending download shows Cancel only.
   * finish a download → result notification has **Open + Close**; Open launches the file with
     a proper app, Close clears the notification; Open on a since-deleted file does nothing
     harmful (no button at all when the file is already gone).
7. **Tabs grid — grouping**: drag tab A onto tab B's card → highlight appears on release-target
   while dragging, release → both grouped, snackbar "Tabs grouped". Target already in a group →
   A joins it. Both ungrouped → new "Group" created. Pinned tabs never highlight. Same group →
   plain reorder.
8. **Tabs grid — header join**: drag an ungrouped tab onto a group header → header highlights,
   release → tab becomes the group's first member.
9. **Tabs grid — header reorder**: long-press a GROUP header, drag up/down past other groups,
   release → the whole block (header + cards) moves; never above the first group, never below
   OTHER; drop back on its own span = no change. PINNED/OTHER headers do not drag.
10. **Tabs grid — regressions**: long-press-release **without moving** → nothing changes;
    swipe a non-pinned card → closes with Undo; pinned cards don't swipe; selection-mode
    long-press still starts selection, not a drag; cross-section drags still respect the
    pinned/normal barrier; drop into a gap → nearest-edge insert. Restart the app → order and
    group positions persisted.
