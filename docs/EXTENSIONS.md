# WebExtensions in MultiSession Browser (GeckoView) — what works, what cannot

Implemented in v2.0.2 (`extensions/ExtensionManager.kt`, `ui/extensions/ExtensionsActivity.kt`, app menu strip).

## Works (real GeckoView `WebExtensionController` wiring)
| Capability | How |
|---|---|
| Install from https `.xpi` URL or local `.xpi` file | `WebExtensionController.install(uri)`; local files are copied to `filesDir/extensions/` first. Gecko validates manifest **and Mozilla signature**. |
| Install permission prompt | `PromptDelegate.onInstallPromptRequest(ext, permissions, origins, dataCollectionPermissions)` → app dialog → `PermissionPromptResponse(granted, privateMode=false, technicalData=false)`. |
| Optional / update permission prompts | `onOptionalPrompt`, `onUpdatePrompt` → Allow/Block dialog. |
| Enable / disable / uninstall | `enable/disable(ext, EnableSource.USER)`, `uninstall(ext)`; state changes mirrored via `AddonManagerDelegate`. |
| Persistence | Gecko keeps installed extensions + enabled state in its profile; the app only lists them (`list()`). |
| browserAction / pageAction | `ActionDelegate.onBrowserAction/onPageAction` → icon/badge/title buttons in the right app menu; tap → `Action.click()`; popup → `onTogglePopup/onOpenPopup` return a `GeckoSession` rendered in an app bottom sheet. |
| `browser.tabs.create/remove/update`, `runtime.openOptionsPage` | `TabDelegate.onNewTab` (tab in the **active** browser session), per-session `SessionTabDelegate.onCloseTab/onUpdateTab`, `onOpenOptionsPage`. |
| Background scripts, content scripts, storage, webRequest, cookies … | Run inside Gecko exactly as in Firefox for Android. |

## Cannot work here (architecture, documented instead of faked)
- **No per-session isolation of extensions.** Extensions are runtime-wide: one install, one `browser.storage`, one background page. They can enumerate tabs of *every* browser session and, with host permissions, read cookies of every contextual identity (`browser.cookies` with `storeId`). GeckoView offers no per-contextId enable/disable.
- No sidebar, devtools panels, `menus`/context-menu UI, omnibox keywords, `commands` (keyboard shortcuts) UI, native messaging, `windows` API UI (single window), `downloads.open()`.
- Only **Mozilla-signed** extensions install in release builds (`ERROR_SIGNEDSTATE_REQUIRED` otherwise).
- Tab-specific action overrides (`session != null` in `onBrowserAction`) are ignored; the menu shows the default action.

## Next steps (if wanted)
- Per-tab action rendering in the tabs sheet; `ensureBuiltIn` for a bundled helper extension (would also give a JS executor and a content-script based media-track killer as an alternative to reload-based revocation).
