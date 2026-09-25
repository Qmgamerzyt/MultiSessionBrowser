# WebExtensions in MultiSession Browser (GeckoView) — what works, what cannot

Implemented in v2.0.2 (`extensions/ExtensionManager.kt`, `ui/extensions/ExtensionsActivity.kt`, app menu strip);
AMO catalog, page `.xpi` interception and the install-prompt host handoff added in v2.1.6 (`extensions/AmoApi.kt`);
"Add extension" by address/slug, add-on details, manual update check and the Firefox Add-ons link added in v2.1.7.

## Works (real GeckoView `WebExtensionController` wiring)
| Capability | How |
|---|---|
| Install from https `.xpi` URL or local `.xpi` file | `WebExtensionController.install(uri)`; local files are copied to `filesDir/extensions/` first. Gecko validates manifest **and Mozilla signature**. |
| Install from an `.xpi` link tapped in a page | `TabDelegates.onExternalResponse` detects `.xpi` / `application/x-xpinstall`, closes the body stream and routes the URL to `install()` — never saved to disk, never installed outside Gecko. |
| Browse + install from addons.mozilla.org | `AmoApi` (official AMO **v5** REST API, `?platform=android`) → search dialog → results → detail (version, users, permissions) → `install()` on `current_version.file.url`. Gecko still fetches and signature-checks the file; requests are honest (no spoofed Firefox identifiers). Offered from the Extensions screen and — when the tab is on an AMO add-on page — from the app menu ("Install add-on from this page"). |
| Install permission prompt | `PromptDelegate.onInstallPromptRequest(ext, permissions, origins, dataCollectionPermissions)` → app dialog → `PermissionPromptResponse(granted, privateMode=false, technicalData=false)`. |
| **Which screen owns the prompt** | The foreground Activity is the `ExtensionHost`: `BrowserActivity` and `ExtensionsActivity` both claim it in `onStart()` and clear it in `onStop()` only if they still hold it (`em.host === this`). v2.1.6 root-cause fix: previously only `BrowserActivity` ever claimed it, so with the Extensions screen in front `host` was `null` and **every install started there was auto-denied** before the user could approve it. |
| Install failures | `AddonManagerDelegate.onInstallationFailed` logs the structured `InstallException.code` and refreshes the list; the user-facing message comes from `installErrorMessage()` (localized, shared by every install surface). One failure => one notification. |
| Optional / update permission prompts | `onOptionalPrompt`, `onUpdatePrompt` → Allow/Block dialog. |
| Enable / disable / uninstall | `enable/disable(ext, EnableSource.USER)`, `uninstall(ext)`; state changes mirrored via `AddonManagerDelegate`. |
| Allow in private sessions | Per-extension overflow toggle → `setAllowedInPrivateBrowsing(ext, allowed)`; reads `metaData.allowedInPrivateBrowsing`. This is the opt-in our private sessions need (they run with `usePrivateMode(session.isPrivate)`), and Gecko's default is **false**. |
| **Add extension by address** (v2.1.7) | The Extensions screen's "Add extension" accepts three shapes: a direct `.xpi` URL, a full `addons.mozilla.org/addon/…` page, or just an add-on slug. AMO pages/slugs are resolved through `AmoApi.detail()` (public v5 API) and only then handed to `install()`; a pasted HTML page is never sent to Gecko as if it were an add-on. |
| Browse Firefox Add-ons (v2.1.7) | Toolbar entry opens `https://addons.mozilla.org/android/` in a browser tab — the official catalog, no third-party store. |
| Add-on details (v2.1.7) | Per-add-on overflow → **Details**: description, version, author, required permissions and the stable id, all read from `WebExtension.MetaData`. No extra network request, nothing synthesized; an AMO/homepage button appears only when the add-on publishes one. |
| Check for updates (v2.1.7) | Per-add-on overflow → **Check for updates** → `WebExtensionController.update(ext)` (`ExtensionManager.checkUpdate()`). Gecko asks AMO for a newer build and installs it itself, so **Mozilla's signature verification runs exactly as it does for a fresh install**; the message compares `metaData.version` before/after, so "No update available" and "Add-on updated" are both literal. |
| Persistence | Gecko keeps installed extensions + enabled state in its profile; the app only lists them (`list()`). |
| browserAction / pageAction | `ActionDelegate.onBrowserAction/onPageAction` → icon/badge/title buttons in the right app menu; tap → `action.click()`; popup → `onTogglePopup/onOpenPopup` return a `GeckoSession` rendered in an app bottom sheet **on the browser screen** (the Extensions screen has no popup surface and closes the session). |
| `browser.tabs.create/remove/update`, `runtime.openOptionsPage` | `TabDelegate.onNewTab` (tab in the **active** browser session), per-session `SessionTabDelegate.onCloseTab/onUpdateTab`, `onOpenOptionsPage`. Tabs are declined while the Extensions screen is in front (needs the browser screen); options pages hand off via `setResult(EXTRA_OPEN_URL)` as the existing menu item does. |
| Background scripts, content scripts, storage, webRequest, cookies … | Run inside Gecko exactly as in Firefox for Android. |

## Cannot work here (architecture, documented instead of faked)
- **No per-session isolation of extensions.** Extensions are runtime-wide: one install, one `browser.storage`, one background page. They can enumerate tabs of *every* browser session and, with host permissions, read cookies of every contextual identity (`browser.cookies` with `storeId`). GeckoView offers no per-contextId enable/disable.
- **Update checks are Gecko's, not ours.** Corrected in v2.1.7: GeckoView 155 *does* expose
  `WebExtensionController.update(WebExtension) -> GeckoResult<WebExtension>` (verified against the
  extracted GV155 `classes.jar`; the earlier claim in this file that the method did not exist was
  wrong, and `WebExtension.update(Download.Info)` — the method this file used to point at — does not
  exist at all). The app now calls it for the manual "Check for updates" entry. Automatic background
  updates still happen inside Gecko, unchanged.
- No sidebar, devtools panels, `menus`/context-menu UI, omnibox keywords, `commands` (keyboard shortcuts) UI, native messaging, `windows` API UI (single window), `downloads.open()`.
- Only **Mozilla-signed** extensions install in release builds (`ERROR_SIGNEDSTATE_REQUIRED` otherwise). The AMO path does not weaken this: the `.xpi` still travels through `install()`.
- The AMO web page's own "Add to Firefox" button can render disabled: it needs `navigator.mozAddonManager` on top-level AMO content, which GeckoView does not expose to us. That is why the app offers the equivalent install through the public API instead of pretending the page button works.
- Tab-specific action overrides (`session != null` in `onBrowserAction`) are ignored; the menu shows the default action.
- No extension-provided context-menu items in the app: `GeckoSession.ContentDelegate.ContextElement`
  does carry an `extensionMenus` list, but that field is package-private in GV155, so the app cannot
  read it. Long-press menus are therefore the app's own link/image/media entries only.

## GeckoView 155 API ground truth (verified by parsing the extracted AAR `classes.jar`)
Every row was read out of the class files in `gv155cls/` — nothing here is inferred from documentation
or from another GeckoView version.

| API | Reality in GV155 | Consequence in the app |
|---|---|---|
| `WebExtensionController.update(WebExtension)` | **Exists**, returns `GeckoResult<WebExtension>` (`lambda$update$9(GeckoBundle) -> WebExtension` proves the payload). | Wired as `ExtensionManager.checkUpdate()` → "Check for updates". |
| `WebExtension.update(...)` | Does **not** exist (no such method on `WebExtension`). | Not referenced anywhere. |
| `BasicSelectionActionDelegate` `getAllActions()/isActionAvailable(String)/prepareAction(String,MenuItem)/performAction(String,MenuItem)` | **protected, non-final**; `<init>(Activity)` public; `getSelection()` public. | `AppSelectionActionDelegate` subclasses it to append Share / Search the web while every built-in action still falls through to `super`. |
| `SelectionActionDelegate.Selection.text` | public field. | Selected text for Share / Search. |
| `ContentDelegate.ContextElement` | Fields: `baseUri`, `linkUri`, `title`, `altText`, `type`, `srcUri`, `linkText`, plus the package-private `extensionMenus`. Constants `TYPE_NONE/IMAGE/VIDEO/AUDIO`. **No `textContent`.** | Link text (`linkText`) is used as the dialog title; a long press on plain text starts a selection instead of a context menu, so text actions live in the selection toolbar — engine behaviour, not a gap. |
| Extension context menus (`menus` API) | Not surfaced to apps (see above). | Documented as unsupported instead of faked. |
| App-side translations (`TranslationsDelegate`) | Not present in GV155. | "Translate" only appears in the selection toolbar if the system offers a `PROCESS_TEXT` handler; the app does not claim a built-in translate feature. |

## Next steps (if wanted)
- Per-tab action rendering in the tabs sheet; `ensureBuiltIn` for a bundled helper extension (would also give a JS executor and a content-script based media-track killer as an alternative to reload-based revocation).
- AMO: pagination (the API returns `next`/`page_count`) and showing `current_version.compatibility.android.min/max` before install.
