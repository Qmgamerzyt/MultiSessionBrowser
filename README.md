# Multi-Session Browser

A custom Android web browser built with GeckoView (Firefox engine) that allows managing multiple isolated browsing sessions simultaneously - perfect for handling multiple accounts of the same website (e.g., 5 different Discord logins).

## Features

- **Multiple Isolated Sessions**: Each session has its own cookies, localStorage, cache, and profile directory
- **Multiple Tabs per Session**: Full tab management within each session
- **Session Switching**: Instantly switch between sessions with full data isolation
- **Floating Ball Overlay**: Hide toolbar, access via draggable edge-snapping floating ball
- **JavaScript Runner**: Type `javascript:code` in omnibox → choose "Search" or "Run in page"
- **Extension Support**: Install from AMO (addons.mozilla.org), manual .xpi files, bundled extensions
- **Per-Session Extension Matrix**: AUTO / OFF / MANUAL modes for each extension per session
- **MANUAL Trigger**: Run extensions/scripts on demand via floating ball, omnibox, or toolbar
- **Built-in Features**: Settings, History, Bookmarks, Downloads, AdBlock, Dark Mode

## Architecture

```
app/
├── engine/           # GeckoView runtime, profile management, storage cleaning
├── session/          # Session lifecycle, switching, persistence
├── tab/              # Tab management, GeckoSession handling
├── extension/        # Extension install/management, native messaging bridge
├── omnibox/          # URL parsing, javascript: handling
├── overlay/          # Floating ball service (WindowManager overlay)
├── ui/               # Jetpack Compose screens
└── data/             # Room database (entities, DAOs, database)
```

## How Session Isolation Works

1. Each session gets a unique profile directory: `filesDir/profiles/session_<uuid>/`
2. When switching sessions:
   - Current session's tabs are saved to Room
   - GeckoRuntime is reinitialized with the target session's profile directory
   - Target session's tabs are restored from Room and reloaded
3. GeckoView's `StorageController` isolates cookies, localStorage, IndexedDB per profile
4. Deleting a session wipes its web data via `StorageController.clearAll()` and removes its profile directory

## MANUAL Mode

Extensions/scripts set to MANUAL:
- Are kept DISABLED for automatic injection
- Can be triggered on-demand via:
  1. Floating ball long-press → "Run Scripts"
  2. Omnibox "Run script" action
  3. Toolbar lightning button
- Uses GeckoView's native messaging bridge to the bundled "runner" extension

## Building

### Requirements
- Android Studio Ladybug or later
- JDK 17
- Android SDK 34

### Steps

```bash
# Clone and open in Android Studio
git clone <repo>
cd multi-session-browser

# Build debug APK
./gradlew assembleDebug

# Install to device
./gradlew installDebug
```

### Gradle Configuration

Key dependencies in `app/build.gradle.kts`:
- GeckoView 128.0 (Mozilla Maven: `https://maven.mozilla.org/maven2/`)
- Jetpack Compose Material 3
- Room 2.6.1
- Hilt 2.50
- OkHttp 4.12

## 📱 HOW TO GET THE APK ON MY PHONE (No PC Needed)

1. **Push code or trigger manually**
   - Push any commit to the `main` branch, OR
   - Go to your repo on GitHub → **Actions** tab → **Build APK** workflow → **Run workflow** → **Run workflow** button

2. **Wait for the build to finish**
   - The workflow runs on GitHub's servers (takes ~3-5 minutes)
   - You'll see a green checkmark when it's done

3. **Download the APK**
   - Click the finished workflow run (e.g., "Build APK #42")
   - Scroll down to **Artifacts**
   - Tap **app-debug** → downloads `app-debug.apk` (or **app-release-unsigned** for the release build)

4. **Install on your phone**
   - Open the downloaded `.apk` file (Chrome/Files app will prompt)
   - If prompted: **Settings → Allow from this source** → toggle ON → Back
   - Tap **Install** → **Open**

> ⚠️ The debug APK is signed with the debug keystore (safe for personal use).
> The release artifact is **unsigned** — you cannot install it directly unless you sign it first.
> To sign the release APK: `apksigner sign --ks my-release-key.jks app-release-unsigned.apk`

## Project Structure

### Engine Layer
- `EngineProvider.kt` - Singleton GeckoRuntime management
- `ProfileManager.kt` - Profile directory per session
- `StorageCleaner.kt` - Per-session data wiping via StorageController

### Session Layer
- `SessionManager.kt` - Create/switch/delete sessions
- `SessionRepository.kt` - Room persistence

### Tab Layer
- `TabManager.kt` - GeckoSession lifecycle, tab switching
- `TabRepository.kt` - Room persistence

### Extension Layer
- `ExtensionManager.kt` - AMO install, .xpi install, built-in ensureBuiltIn
- `ExtensionPromptDelegate.kt` - Permission prompts
- `ExtensionSessionMatrix.kt` - Per-session AUTO/OFF/MANUAL state
- `ManualRunner.kt` - Native messaging bridge for MANUAL triggers
- `JsRunner.kt` - Execute arbitrary JS via runner extension

### Omnibox
- `OmniboxParser.kt` - URL/Search/JavaScript classification
- Two-option dialog for `javascript:` input

### Overlay
- `FloatingBallService.kt` - WindowManager TYPE_APPLICATION_OVERLAY
- Draggable with edge-snap, tap to restore, long-press for radial menu

### UI Screens (Compose)
- `BrowserScreen.kt` - Main screen with GeckoView
- `TabSwitcherScreen.kt` - Grid of tabs
- `SessionSwitcherScreen.kt` - Session list with colors/icons
- `ExtensionManagerScreen.kt` - Matrix UI for extensions
- `SettingsScreen.kt` - All settings
- `HistoryScreen.kt`, `BookmarksScreen.kt`, `DownloadsScreen.kt`

## Bundled Extensions

### Runner (`assets/runner/`)
Internal helper extension for:
- Executing arbitrary JS in active tab
- Running MANUAL scripts via native messaging
- Manifest permissions: `geckoViewAddons`, `nativeMessaging`, `activeTab`, `scripting`, `<all_urls>`

### AdBlock (`assets/adblock/`)
Simple declarative blocking of common ad/tracker domains

## Testing Session Isolation

1. Open Session A → Log into Discord (account 1)
2. Create Session B → Open Discord → Must be logged out
3. Switch back to Session A → Still logged in
4. Session B cookies/localStorage never leak to Session A

## Permissions

- `INTERNET`, `ACCESS_NETWORK_STATE` - Basic browsing
- `SYSTEM_ALERT_WINDOW` - Floating ball overlay
- `FOREGROUND_SERVICE` - Overlay service
- `POST_NOTIFICATIONS` - Download notifications
- `WRITE_EXTERNAL_STORAGE` (legacy) - File downloads

## License

MIT