# BUGFIX_TABSSHEET_CRASH.md — v2.1.3 crash investigation record (2.1.2 → 2.1.3)

## Reported symptom (v2.1.2, versionCode 10)

1. **Instant crash when opening the tabs button.**
2. **Instant crash when creating a tab group.**

Both paths were traced to their only common call:

* tabs button → `BrowserActivity.kt` `tabsButton` listener → `exitSearchMode(); TabsSheet().show(supportFragmentManager, "tabs")`
* menu → "New group" → `GroupEditDialog.show(...)` → `core.tabs.createGroup(...)` → `TabsSheet().show(supportFragmentManager, "tabs")`

`exitSearchMode()` itself is shared with the **working** menu/sessions buttons (it runs on every menu
drawer open), so it is excluded by behavioural evidence. `createGroup()` is pure in-memory map +
`core.persist { … }` and cannot throw before the sheet is shown. → the throw happens inside the
`TabsSheet` fragment lifecycle (`onCreateView` → `onViewCreated` → `rebuild()` → first RecyclerView
layout → `onStart`).

## Verification performed (source-level; every check run against this tree)

| # | Hypothesis | Evidence | Result |
|---|---|---|---|
| 1 | `findViewById` null (ID missing from inflated layout) | All 17 sheet IDs, both dialog IDs, all 10 `item_tab` IDs, all 6 `item_tab_group` IDs, all 8 menu IDs greped **per defining file** | ✅ present |
| 2 | Format-string arg mismatch (`IllegalFormatException`) | Parsed **all 4** strings files with Python: every `%` specifier positional-sequential, counts match call sites (`tab_count_fmt`, `tabs_count_fmt`, `archived_count_fmt`, …) | ✅ clean |
| 3 | `PALETTE[(0..7).random()]` out of bounds | `SessionManager.PALETTE` has exactly **8** entries | ✅ safe |
| 4 | Custom `<ViewClass>` tag missing (AAPT never checks these) | Every XML tag in all layouts classified: framework / `androidx.*` / `com.google.*` (all in pinned deps) / app classes — none missing | ✅ resolves |
| 5 | `Tab.displayTitle` / `UrlUtils.displayHost` throw | `when`-expression + `try/catch … return url` | ✅ safe |
| 6 | `Tab.isLive` touches Gecko | `geckoSession != null` | ✅ trivial |
| 7 | `BrowserActivity.currentTabIdOrNull` | plain getter over `currentTabId` | ✅ trivial |
| 8 | `TabManager` reads hit Room on main thread | `pinnedTabs/groupsFor/tabsInGroup/ungroupedTabs/countFor/archivedCountFor` are in-memory `LinkedHashMap` filters | ✅ safe |
| 9 | Listener iteration `ConcurrentModificationException` | `listeners = CopyOnWriteArraySet<Listener>` | ✅ impossible |
| 10 | Room schema / migration failure on group insert | DB opens at process start (`loadFromDb`); app reaches browsing ⇒ schema valid; `version = 5` + destructive-downgrade policy | ✅ excluded |
| 11 | Theme missing `colorPrimary` → `MaterialColors.getColor` throws | `Theme.MultiSessionBrowser` (day **and** night) sets `colorPrimary`; `Theme.Material3.DayNight.NoActionBar` parent defines it; sheet inflates with dialog theme derived from activity theme | ✅ resolves |
| 12 | Stale/duplicate `TabsSheet` class referencing removed IDs | exactly one `class TabsSheet`; `sheetTitle`/`closeAllButton`/`reopenButton` referenced nowhere | ✅ excluded |
| 13 | `lateinit` use-before-init (`suggestionPopup`, adapter, views) | init order in `onViewCreated` verified; menu button (which calls the same `exitSearchMode`) works ⇒ shared fields initialized | ✅ excluded |
| 14 | R8/resource shrinking stripping a ref | `isMinifyEnabled = false` both build types, no `shrinkResources` | ✅ N/A |
| 15 | Dependency version skew → `NoSuchMethodError` at runtime | all versions pinned in one classpath, no `force`/`resolutionStrategy` overrides in Gradle or workflow | ✅ impossible |
| 16 | Framework `NewApi` call on device | sheet path uses only API ≤ 28 primitives (`minSdk = 28`) | ✅ safe |
| 17 | `BottomSheetBehavior.setState` invalid state | only `STATE_EXPANDED` + `skipCollapsed`, both legal pre-layout | ✅ safe |
| 18 | RecyclerView reentrancy (`notify*` during layout) | mutation sources are click/`post{}`/handler-queued; no mutation during layout | ✅ safe |
| 19 | Stable-ID / view-type inconsistency | `getItemViewType` derived from the same `rows` list, mutated only with matching `notify*` | ✅ consistent |
| 20 | `GroupEditDialog` inflate (`dialog_group_edit.xml`) | plain `LinearLayout`/`EditText`/`HorizontalScrollView`, both IDs present | ✅ clean |
| 21 | Grid span lookup OOB | `rows.getOrNull(position)` null-safe | ✅ safe |
| 22 | Duplicate layout ID (`newTabButton` in toolbar + sheet) | same ID in two layouts is legal; each `findViewById` resolves to its own tree | ✅ legal |

**Conclusion:** no statically reproducible defect exists on either path. The throw is runtime-only
(device/API-level/Gecko-state dependent), so the **exact throwing frame can only come from a real
stack trace**.

## Fix shipped in 2.1.3 (honours "no try/catch masking, find the exact frame")

* `BrowserApp.onCreate` installs a default uncaught-exception handler that **writes the full stack
  trace (exception chain + all frames) to `filesDir/crash_trace.txt` first, then delegates to the
  previous system handler unchanged** — the app still crashes exactly as before; nothing is caught
  around, suppressed, or altered. The only `runCatching` guards the trace *file write* itself.
* On the next launch `BrowserActivity.showCrashTraceIfAny()` displays the captured trace once in a
  dialog and consumes the file.

### User steps to complete the root-cause fix (2.1.4)

1. Install `MultiSessionBrowser-2.1.3-*` (versionCode 11 — installs over 2.1.2).
2. Tap the tabs button (or create a tab group) so it crashes once.
3. Re-open the app → the "Crash captured" dialog shows the exact stack → screenshot it.
4. The 2.1.4 release fixes the exact throwing frame named in that trace.

*(Alternative with zero code: enable Wireless debugging and provide a pairing code for adb logcat.)*

## Also in 2.1.3 (independent of the crash)

* **Bug 2:** undo/redo header buttons removed (fields, binds, listeners, alpha lines, dead
  `hudUndo`/`hudRedo`). Long-press "reopen closed tab" remains available via the menu entry.
* **Bug 3:** HUD de-duplicated — customizable set is now `top,bottom,closetab`
  (`Prefs.DEFAULT_HUD`); stale 2.1.2 preferences are filtered on read; `undo`/`redo` HUD items and
  their non-functional `Actions` methods removed; the customize dialog only offers the kept set.
* **New:** fullscreen nub — while the toolbar is hidden, a small draggable button sits on the right
  edge; tapping it reveals the full original HUD option set (back/forward/reload/top/bottom/
  desktop/fullscreen/newtab/closetab/find) — nothing in the full set is duplicated while the
  toolbar is hidden. *(Superseded in 2.1.5 — see the resolution below: the nub is now a plain
  floating close-fullscreen button.)*

## Resolution — runtime trace delivered, root cause proven, fixed in 2.1.5 (versionCode 13)

The trace was captured by the 2.1.3 handler and delivered by the 2.1.4 "Crash captured"
notification (Copy button), e.g. `time=2026-09-24 15:02:54.627`:

```
android.view.InflateException: Binary XML file line #107: You must supply a layout_width attribute.
  at android.view.LayoutInflater.inflate(LayoutInflater.java:423)
  ...
  at app.multisession.browser.ui.tabs.TabsSheet.onCreateView(TabsSheet.kt:99)
--- cause ---
java.lang.UnsupportedOperationException: Binary XML file line #107: You must supply a layout_width attribute.
  at android.content.res.TypedArray.getLayoutDimension(TypedArray.java:779)
  at android.view.ViewGroup$LayoutParams.setBaseAttributes(ViewGroup.java:7870)
  at android.view.ViewGroup$MarginLayoutParams.<init>(ViewGroup.java:8062)
  at android.widget.LinearLayout$LayoutParams.<init>(LinearLayout.java:1997)
  at android.widget.LinearLayout.generateLayoutParams(LinearLayout.java:1895)
  at android.widget.LayoutInflater.rInflate(LayoutInflater.java:882)
```

**Root cause (a plain XML defect — NOT device/API/runtime dependent):**

* `TabsSheet.kt:99` inflates `res/layout/sheet_tabs.xml`.
* Line **107** of that file is `<Button android:id="@+id/bulkSelectAll" style="@style/Widget.Material3.Button.TextButton" …>`
  — and **all seven bulk-selection buttons (lines 107–113) declare neither `android:layout_width`
  nor `android:layout_height`.**
* `LinearLayout.generateLayoutParams(attrs)` reads those attributes from a `TypedArray` that also
  consults the element's `style=`, but the Material `Widget.Material3.Button.TextButton` style
  supplies no `layout_width`/`layout_height` → `getLayoutDimension()` throws. The stack's four
  `rInflate` frames match the file nesting exactly: sheet root → `selectionBar` (LinearLayout) →
  `HorizontalScrollView` → inner `LinearLayout` → `Button` at line 107.
* The selection bar carries `android:visibility="gone"`, but `LayoutInflater` inflates every
  element regardless of visibility — so opening the tabs sheet threw **every time, on every
  device**. The 22-check static investigation missed it because it checked code/IDs/strings/format
  specifiers, not raw `layout_*` attributes on leaf elements.
* Every other element in the file (and, by a full audit of every element in `res/layout*/`, in the
  whole app) either declares `layout_*` explicitly or receives them from a repo-local style
  (`@style/CompactBarButton`, `@style/SectionTitle` both define `layout_width`/`layout_height`) —
  the seven `TextButton`s were the only genuine offenders in the app.

**Fix:** the seven buttons now declare `android:layout_width="wrap_content"` and
`android:layout_height="wrap_content"` (standard TextButton row sizing inside the horizontal
`LinearLayout`). No try/catch, no defensive wrapper — the exact throwing frame no longer exists.

**Timeline correction:** the plan above said "2.1.4 fixes the frame" — in reality 2.1.4 shipped the
stable signing key + the Copy/Share notification (it is what *delivered* this trace); the frame fix
itself lands in **2.1.5**.

## Also in 2.1.5 (user-requested UI changes)

* **"Show HUD" and "Customize HUD…" drawer-menu entries removed completely** — both `entry(...)`
  lines in `BrowserActivity.renderMenu()` are gone; the HUD pill remains, unchanged.
* **Fullscreen close:** the nub is now a plain floating **close-fullscreen button** (44dp, `ic_close`)
  shown only while the toolbar is hidden; a tap exits fullscreen via `Actions.hudToggleToolbar()`,
  and the button disappears as fullscreen ends. The nub-popup path (`popupOpen`/`togglePopup`/
  `collapsePopup`) was removed along with it.
