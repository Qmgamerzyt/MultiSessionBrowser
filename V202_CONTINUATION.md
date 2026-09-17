# v2.0.2 continuation / status record

Built from the v2.0.1 project state (all user build fixes kept: AGP 9.2.1 + KGP 2.4.20 classpath, compileSdk 37.1, GeckoView 155.0.20260903215306).
**Not compiled in this environment** (no Gradle/SDK) — first CI run is the real test. Static checks passed: bracket balance (template-aware),
every `R.*` / `@string` / `@drawable` / `@layout` / `@id` reference exists, no stale WebView/DownloadHandler/SessionsSheet references, no conflict markers.

## Completed
| # | Item | Where |
|---|---|---|
| 1 | Mobile-mode WebRTC audio | `engine/GeckoEngine.kt` (`writeConfigFile`: media.setsinkid.enabled, full_duplex; `configFilePath`), `BrowserActivity.onMediaPermissionRequest` (mic source fallback), per-site Desktop rule (`SitePermissionType.DESKTOP_SITE`, `TabDelegates.applyDesktopSiteRule`), Settings switch `webrtc_compat` |
| 2 | Camera/mic revocation | `SitePermissionStore.set` → `TabManager.revokeMedia` (reload live pages of the origin/session) |
| 3 | Tab reordering | `ui/tabs/TabsSheet.kt` (ItemTouchHelper), `TabManager.applyOrder`, persisted via `tabs.position` |
| 4–6 | Tab groups + empty-group persistence + persistence | `TabGroupEntity`/`tab_groups`, `tabs.groupId`, `TabManager` group API, `TabsSheet`, `GroupEditDialog`, migration 3→4 |
| 7 | Fast tab switching | unchanged GeckoSession swap; loading optimisations untouched |
| 8 | Session reordering + default rule | `sessions.isDefault`, `SessionDao` order `isDefault DESC, sortOrder`, `SessionManager.reorder/setDefault`, cold start opens default, `ui/sessions/SessionsDrawer.kt` (pinned row 0) |
| 9–13 | Session drawer, nav controls, central bar, tabs button, right app menu | `layout/activity_browser.xml` (DrawerLayout), `drawer_sessions.xml`, `drawer_menu.xml`, `ui/browser/AppMenu.kt`, `BrowserActivity.renderMenu` |
| 14 | Selectable HUD | `ui/browser/HudController.kt`, `view_hud.xml`, Prefs `hud_visible`/`hud_items` |
| 15 | Site permissions in app menu | menu entry + lock icon; dialog unchanged (+ Desktop site row) |
| 16–17 | Download manager + type handling | `downloads/AppDownloadManager.kt`, `downloads/DownloadTypes.kt`, `ui/downloads/DownloadsActivity.kt`, `downloads` table, `REQUEST_INSTALL_PACKAGES` (installer only after confirmation) |
| 18 | javascript: URLs | `UrlUtils.isJavaScriptUrl/resolveInput`, `TabManager.runScript`, `Tab.pendingScript`, `TabDelegates.onLoadRequest` |
| 19 | WebExtensions foundation | `extensions/ExtensionManager.kt`, `ui/extensions/ExtensionsActivity.kt`, `docs/EXTENSIONS.md` |
| – | Version | `versionCode 7`, `versionName 2.0.2` |

## Most likely compile-fix spots (if CI fails)
1. `ExtensionManager.promptDelegate`: signatures follow GeckoView 158 javadoc (`onInstallPromptRequest(ext, perms, origins, dataCollectionPermissions)`, `onUpdatePrompt(ext, newPerms, newOrigins, newDataCollection)`, `PermissionPromptResponse(Boolean, Boolean, Boolean)`). If 155 differs: adjust arity to the compiler message; the 3-arg `PermissionPromptResponse` is the only ctor in current docs.
2. `WebExtension.SessionTabDelegate.onCloseTab/onUpdateTab` return types: change to `GeckoResult<AllowOrDeny>?` if the compiler complains about nullability.
3. `LoadRequest.isDirectNavigation` (GeckoView ≥ 80) — if missing, drop that condition in `TabDelegates` (keep `request.uri == pending`).
4. `WebRequest.Builder.referrer/cacheMode` — remove if not present in 155.
5. Room: entity `SessionEntity.isDefault` uses `@ColumnInfo(defaultValue="0")` to match the migration SQL; `TabEntity.groupId` has no index on purpose (migration adds none).

## Device verification checklist
- javascript: from the bar runs in the page and the page is NOT replaced (if GeckoView refuses the load, fallback: bundled helper extension with `tabs.executeScript` — see docs/EXTENSIONS.md next steps).
- WebRTC test page in mobile mode: `enumerateDevices()` lists audioinput + audiooutput after permission.
- Block camera in Site permissions during a call → page reloads, camera LED off, no re-prompt.
- Drag tabs/groups, kill app, reopen: order/groups/empty groups intact; default session opens.
- Download pause/resume/cancel/retry; APK opens installer only after confirmation.

## Not done / open
- Extension per-tab action overrides and a bundled helper extension (optional).
- Settings screen has only the WebRTC switch added; HUD config lives in the app menu.
