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
  toolbar is hidden.
