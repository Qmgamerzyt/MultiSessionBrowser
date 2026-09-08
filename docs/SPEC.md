Build a Complete Multi-Session WebView Browser/HTML-to-APK Android App From Scratch

You are the lead Android engineer, UI/UX designer, WebView specialist, and QA engineer for this project.

I want you to build a complete Android mobile application from scratch using HopWeb.

The application is essentially an advanced HTML-to-APK/WebView platform with a powerful multi-session browser system.

The app must NOT behave like a simple WebView wrapper.

It must provide:

- Real Android WebView
- HTML/website loading
- Multiple independent sessions
- Multiple tabs inside every session
- Completely separated cookies
- Completely separated login states
- Separate localStorage/sessionStorage
- Separate cache/data where technically possible
- Session persistence
- Tab persistence
- Chrome-like multitasking
- Session switching
- Tab switching
- Smart navigation
- Back/forward handling
- Downloads where possible
- File uploads
- JavaScript support
- Modern website compatibility
- Secure storage
- Good Android UX
- Proper lifecycle handling
- Offline/local HTML support
- APK-ready production architecture

Do not create a fake browser UI that merely changes URLs.

Every website must actually run inside a real Android WebView.

---

1. IMPORTANT DEVELOPMENT RULE

Before implementing anything:

1. Inspect the HopWeb environment and determine:
   
   - What project structure HopWeb supports
   - What Android capabilities are available
   - Whether native Android/Kotlin/Java code can be generated
   - Whether WebView APIs can be accessed directly
   - What permissions are available
   - What libraries/dependencies are supported
   - How APK builds are configured
   - What limitations HopWeb has

2. If HopWeb has limitations, design the closest production-quality architecture possible.

3. Do NOT silently replace real WebView functionality with:
   
   - iframe
   - fake browser rendering
   - HTTP fetching
   - remote screenshot rendering
   - a simulated login system

The application must use an actual Android WebView whenever the platform allows it.

4. If a requested feature is impossible in pure HopWeb, clearly identify the limitation and implement the best native-compatible architecture available.

5. Do not stop after creating a prototype.

Build the project as a complete application.

---

2. CORE CONCEPT

The app should have two levels:

Level 1 — Sessions

A Session represents a completely separate browsing identity.

Example:

Session 1:

- Personal
- logged into Account A

Session 2:

- Work
- logged into Account B

Session 3:

- Testing
- logged into Account C

These sessions must not accidentally share authentication state.

A session should maintain its own browsing environment.

---

3. SESSION ISOLATION

This is one of the most important requirements.

Each session should have its own:

- Cookies
- Login state
- LocalStorage
- SessionStorage where technically possible
- IndexedDB where technically possible
- Web database/storage
- Cache where technically possible
- WebView data directory/profile where Android supports it
- Browser history
- Tabs
- Current URLs
- Tab titles
- Favicons if available
- Form state where appropriate

Do NOT use one global WebView data store for all sessions if the Android WebView architecture allows a better isolated solution.

Research the correct Android WebView mechanisms for isolated profiles/data directories.

The architecture should be designed so:

Session A cannot accidentally inherit cookies from Session B.

Example:

Session A:
google.com → Account A

Session B:
google.com → Account B

Opening google.com in Session A must remain Account A.

Opening google.com in Session B must remain Account B.

---

4. MULTIPLE TABS PER SESSION

Every session can contain multiple tabs.

Example:

SESSION: Work

Tab 1:
Google

Tab 2:
Gmail

Tab 3:
Discord

Tab 4:
Dashboard

SESSION: Personal

Tab 1:
YouTube

Tab 2:
Reddit

Tab 3:
Google

Each tab must maintain its own:

- URL
- WebView state
- Navigation history
- Title
- favicon if available
- scroll position where possible
- JavaScript state
- form state
- page state

Switching tabs should not reload the page unnecessarily.

---

5. SESSION + TAB DATA MODEL

Design a clean data model.

Example conceptual structure:

App
├── Sessions
│    ├── Session A
│    │    ├── Tab 1
│    │    ├── Tab 2
│    │    └── Tab 3
│    │
│    ├── Session B
│    │    ├── Tab 1
│    │    └── Tab 2
│    │
│    └── Session C
│         └── Tab 1

Each Session should contain:

- unique ID
- name
- icon/color
- creation date
- last used date
- active tab ID
- tab list
- session settings
- isolated WebView/profile identifier

Each Tab should contain:

- unique ID
- session ID
- URL
- title
- favicon
- creation time
- last active time
- loading state
- WebView reference/state
- navigation state

Do not use array indexes as permanent IDs.

Use stable unique identifiers.

---

6. MAIN APP SCREEN

Create a modern mobile browser-style interface.

The main screen should make it very easy to understand:

Current Session:
"Work"

Current Tabs:
[Google] [Gmail] [Discord] [+]

The user should be able to:

- switch session
- create session
- rename session
- delete session
- duplicate session if technically supported
- switch tabs
- create tab
- close tab
- reopen recently closed tab
- move between tabs
- search/navigate
- go back
- go forward
- reload
- stop loading
- open external browser when necessary

---

7. SESSION SWITCHER

Create a dedicated Session Manager.

Possible UI:

---

Sessions

🟢 Personal
3 tabs

🔵 Work
5 tabs

🟣 Testing
2 tabs

+ New Session

---

Tapping a session switches to that session.

The UI should clearly indicate the active session.

Each session may have:

- custom name
- icon
- color
- optional description

Example:

"Personal"
"Work"
"Discord Alt"
"Testing"

---

8. TAB SWITCHER

Implement a Chrome-like tab overview.

For example:

---

Work
5 Tabs

[Google]
google.com

[Gmail]
mail.google.com

[Discord]
discord.com

[Dashboard]
example.com

[+ New Tab]

Users can:

- tap a tab
- close a tab
- swipe/delete a tab
- create a tab
- reorder tabs if practical
- keep tabs alive when switching

Do not destroy and recreate WebViews unnecessarily.

---

9. WEBVIEW ENGINE

Implement a proper Android WebView.

Enable appropriate modern WebView functionality including, where supported:

- JavaScript
- DOM storage
- database storage
- cookies
- mixed-content handling only when necessary
- file access where safe
- content access
- multiple windows if needed
- viewport support
- responsive layout
- zoom where appropriate

Use secure defaults.

Do not blindly enable unsafe settings.

Research the current recommended Android WebView configuration.

---

10. JAVASCRIPT

Websites must work normally.

Support:

- JavaScript
- AJAX/fetch
- WebSockets
- dynamic websites
- SPA applications
- React applications
- Vue applications
- modern authentication flows
- redirects
- popups where appropriate

Do not inject unnecessary JavaScript into websites.

---

11. COOKIES

Implement proper cookie management.

Requirements:

- Cookies enabled
- Persistent cookies
- Session cookies
- Secure cookies
- HttpOnly cookies handled by WebView
- SameSite behavior handled by WebView
- Cookie persistence after app restart
- Cookie separation between sessions

Do not manually copy cookies between sessions.

Do not expose cookies to JavaScript unless absolutely necessary.

Provide a session-level option:

"Clear Cookies"

and potentially:

"Clear Site Data"

---

12. LOGIN PERSISTENCE

If a user logs into a website:

Session A:

username@example.com

The login should remain after:

- switching tabs
- switching sessions
- closing/reopening the app
- restarting Android

provided the website itself permits persistent authentication.

However:

Session B must NOT automatically receive Session A's login.

This must be tested thoroughly.

---

13. LOCAL HTML / HTML TO APK FUNCTIONALITY

The application should support loading local HTML.

Users should be able to:

- enter/paste HTML
- import an HTML file
- import a folder/project if supported
- load local CSS
- load local JavaScript
- load local images/assets
- preview the HTML in a WebView

Possible workflow:

Create App
→ Enter HTML
→ Preview
→ Configure App
→ Build/Export APK

If HopWeb cannot actually compile APK files dynamically inside the Android app, do NOT fake this functionality.

Instead, separate the product into:

1. WebView runtime application
2. Project/configuration system
3. Build/export functionality where technically supported by HopWeb

Clearly document platform limitations.

---

14. START PAGE

Create a clean start page.

Show:

[ + New Tab ]

Search or enter URL

Recent Sessions

Recent Tabs

Favorites/Shortcuts

Example:

Welcome

[ Search or enter URL ]

Sessions
Personal
Work
Testing

Recent
Google
Discord
YouTube

---

15. URL / SEARCH BAR

Create a browser-style address bar.

Behavior:

If input looks like a URL:

https://example.com

open it directly.

If input is not a URL:

search using configured search engine.

Examples:

"cats"

→ search engine

"https://example.com"

→ website

"example.com"

→ attempt https://example.com

Support:

- HTTPS
- HTTP where technically allowed
- deep links where appropriate

---

16. NAVIGATION

Every tab must support:

- Back
- Forward
- Reload
- Stop
- Home
- URL navigation

Android system back button:

1. If WebView can go back → go back
2. Otherwise → close/exit tab according to app design
3. Do not unexpectedly close the entire app

Handle lifecycle correctly.

---

17. POPUPS / NEW WINDOWS

Some websites use:

window.open()

or target="_blank".

Implement proper WebView multiple-window handling where possible.

Options:

- open in a new tab
- open in current tab
- open externally

Prefer opening supported web links as new tabs rather than losing the current page.

---

18. FILE UPLOAD

Support HTML:

<input type="file">Users should be able to upload:

- images
- videos
- documents
- multiple files where supported

Handle Android file picker permissions correctly.

Support camera capture where appropriate if technically possible.

Do not request unnecessary permissions.

---

19. DOWNLOADS

Support website downloads where Android allows it.

Implement:

- download detection
- Android DownloadManager where appropriate
- notification/progress if possible
- download filename handling
- MIME type handling

Respect Android storage restrictions.

---

20. PERMISSIONS

Handle WebView website permissions properly.

Potential permissions:

- Camera
- Microphone
- Location
- Notifications
- Storage/file picker

Do not automatically grant permissions.

When a website requests permission:

Show a clear Android-style permission flow.

Maintain permission decisions appropriately.

Ideally permissions should be manageable per session/site where practical.

---

21. CAMERA + MICROPHONE

Support WebRTC websites where technically possible.

Examples:

- video calls
- voice calls
- browser camera
- microphone recording

Handle:

- Android runtime permissions
- WebChromeClient permission requests
- lifecycle
- permission denial
- permission cancellation

Test with real websites.

---

22. NOTIFICATIONS

If websites request notifications:

Implement the correct Android WebView/notification architecture if supported.

Do not pretend WebView notifications work if Android/WebView does not support them directly.

Clearly handle unsupported cases.

---

23. WEBVIEW SECURITY

Security is extremely important.

Implement:

- HTTPS-first behavior
- safe URL handling
- restricted file access
- restricted universal access from file URLs
- safe JavaScript bridge design
- no arbitrary native method exposure
- protection against malicious local HTML accessing sensitive files
- no hardcoded secrets
- secure storage for app configuration
- safe external intent handling

Do not create a JavaScript interface exposing dangerous native functions.

---

24. EXTERNAL LINKS

Some websites cannot be embedded in WebView because of:

- X-Frame-Options
- Content-Security-Policy
- unsupported browser behavior

IMPORTANT:

Those restrictions apply to iframes, not necessarily to navigating the WebView directly.

Therefore:

Do NOT implement the browser using iframe.

Navigate the real WebView directly to the website.

If a website itself refuses WebView access or requires a normal browser, provide:

"Open in external browser"

Do not show a fake iframe error page.

---

25. SESSION STORAGE ARCHITECTURE

Create a persistent local database/configuration system.

Store:

Sessions
Tabs
Settings
History
Favorites
App configuration

Use an appropriate Android persistence mechanism available in HopWeb.

Prefer:

Room/SQLite

or the best supported persistent storage mechanism.

Do not store everything in fragile in-memory variables.

---

26. WEBVIEW LIFECYCLE

This is critical.

Android can kill activities/processes due to memory pressure.

Implement proper lifecycle handling.

The app should:

- restore sessions
- restore tabs
- restore URLs
- recreate WebViews safely
- avoid memory leaks
- release inactive WebViews when necessary
- preserve browser state where possible
- avoid crashing with many tabs

Design a WebView lifecycle manager.

---

27. MANY TABS

The user may open many tabs.

Do not assume only 3–5 tabs.

Design for:

10+
20+
30+

tabs where device memory allows.

Use memory-conscious WebView management.

Possible strategy:

Active tabs:
fully alive

Background tabs:
kept alive when reasonable

Memory pressure:
save state and destroy/recreate WebView

Do not destroy all tabs whenever the user changes tabs.

---

28. SESSION MEMORY ISOLATION

Do not confuse:

"tab isolation"

with

"session isolation".

Tabs within the same session SHOULD share that session's authentication environment.

Example:

Session A:

Tab 1 → Gmail
Tab 2 → Google Calendar

Both should use Session A's login.

But:

Session B:

Tab 1 → Gmail

must have a separate login environment.

This distinction is fundamental.

---

29. SESSION DUPLICATION

If technically possible, provide:

"Duplicate Session"

This should create a new session.

However, carefully consider security.

Possible options:

Duplicate session with:

- tabs only
- tabs + browsing state
- cookies/login state

Do not duplicate authentication cookies accidentally.

If cookie duplication is technically unsafe or unsupported, duplicate only tabs/URLs and start a fresh isolated session.

---

30. SESSION RESET

Each session should have:

Reset Session

Options:

- Clear cookies
- Clear cache
- Clear site storage
- Clear history
- Clear everything

Confirmation:

"This will permanently remove login data and website storage for this session."

---

31. PRIVATE SESSION

Add an optional:

"Private Session"

Private sessions should:

- avoid persistent cookies
- avoid persistent browsing history
- avoid saving unnecessary data
- be destroyed when closed

Clearly explain what private mode does and does not protect.

Do not falsely claim that private mode makes the user anonymous.

---

32. HISTORY

Implement per-session history.

History should include:

- URL
- title
- timestamp
- session ID

Example:

Work
Today
Google
Gmail
Discord

Personal
Today
YouTube
Reddit

History from Session A should not automatically appear inside Session B if session isolation is enabled.

---

33. FAVORITES / BOOKMARKS

Support bookmarks.

Decide whether bookmarks are:

- global
  or
- per-session

Prefer allowing both:

Global bookmarks

and optional:

Session bookmarks

---

34. UI DESIGN

Use a modern Android design.

The interface should feel like a real mobile browser rather than a developer demo.

Requirements:

- clean spacing
- rounded components where appropriate
- smooth animations
- dark mode
- light mode
- responsive layouts
- accessible text
- clear icons
- touch-friendly controls

Do not overcrowd the interface.

---

35. MAIN NAVIGATION

Suggested bottom navigation:

Home
Sessions
Tabs
Settings

Or design a better architecture if you have a stronger UX solution.

The WebView itself should have a minimal browser toolbar.

Example:

←   →   ⟳

[ example.com                     ]

⋮

Then website content.

---

36. TAB COUNTER

Show current tab count.

Example:

[ 5 ]

Tapping it opens tab overview.

---

37. SESSION INDICATOR

Always make the current session obvious.

Example:

● Work

or:

Work ▾

Tapping it opens the session switcher.

This prevents the user from accidentally operating in the wrong account/session.

---

38. SETTINGS

Create a complete Settings screen.

Sections:

General

- Default search engine
- Homepage
- Open links behavior
- Startup behavior

WebView

- JavaScript
- Zoom
- User agent
- Desktop site
- Media autoplay
- Third-party cookies
- Safe browsing where available

Sessions

- Default session
- Session startup behavior
- Private session behavior
- Clear session data

Downloads

- Download location
- Ask before downloading

Privacy

- Clear history
- Clear cookies
- Clear cache
- Clear site data

Appearance

- Light
- Dark
- System

---

39. DESKTOP SITE

Provide:

"Request Desktop Site"

per tab or globally.

When enabled, configure WebView appropriately.

Remember the setting per tab/session where appropriate.

---

40. CUSTOM USER AGENT

Allow advanced users to configure a custom User-Agent.

However, do not unnecessarily spoof browsers.

Provide:

Default

Mobile

Desktop

Custom

Only enable custom UA if technically supported.

---

41. ERROR HANDLING

Create proper error pages.

Examples:

No Internet

Website unavailable

SSL error

Unsupported page

Download failed

Permission denied

WebView crashed

Do not show generic developer errors to users.

Provide:

Retry

Go Back

Open External Browser

---

42. OFFLINE HTML

The app must be capable of loading local HTML files.

Design a secure local-content architecture.

Example:

Project:

myapp/
├── index.html
├── css/
├── js/
├── images/
└── assets/

The WebView should correctly resolve relative paths.

Avoid insecure file:// architecture when a safer WebView asset/content loading mechanism is available.

Prefer Android WebView asset/content APIs where supported.

---

43. PROJECT SYSTEM

Allow users to create projects.

Example:

My Website

Files:
index.html
style.css
script.js
assets/

Project settings:

- App name
- Package name
- Version
- Icon
- Splash screen
- Orientation
- Theme
- Start URL/file

Then:

Preview

Build/Export

If dynamic APK building cannot be performed by HopWeb itself, create the project/export package required by the supported build workflow.

---

44. APK CONFIGURATION

Provide configuration for:

- App name
- Package ID
- Version name
- Version code
- App icon
- Splash screen
- Orientation
- Status bar
- Navigation bar
- Theme
- Permissions

The final generated Android project should be buildable into an APK whenever the HopWeb environment supports Android project generation.

---

45. DO NOT CREATE A TOY APP

This must NOT be a simplistic project containing:

MainActivity
WebView
loadUrl()

and nothing else.

The goal is a real browser-like WebView runtime with a session architecture.

The code must be modular and maintainable.

---

46. CODE ARCHITECTURE

Use a clean architecture.

Suggested conceptual structure:

app/
├── core/
│    ├── WebViewManager
│    ├── SessionManager
│    ├── TabManager
│    ├── PermissionManager
│    ├── DownloadManager
│    └── LifecycleManager
│
├── data/
│    ├── database/
│    ├── repositories/
│    └── models/
│
├── webview/
│    ├── WebViewFactory
│    ├── WebViewConfiguration
│    ├── CookieManager
│    ├── WebChromeHandler
│    ├── WebViewClient
│    └── DownloadHandler
│
├── session/
│    ├── Session
│    ├── SessionStorage
│    └── SessionIsolation
│
├── tabs/
│    ├── Tab
│    ├── TabManager
│    └── TabState
│
├── ui/
│    ├── Home
│    ├── Sessions
│    ├── Tabs
│    ├── Browser
│    └── Settings
│
└── utils/

Adapt this structure to whatever technology HopWeb actually supports.

Do not blindly follow this exact folder structure if HopWeb requires a different architecture.

---

47. STATE MANAGEMENT

Create a single source of truth for:

- current session
- current tab
- sessions
- tabs
- navigation state
- loading state
- permissions
- settings

Avoid duplicated state that can become inconsistent.

---

48. RESTORE AFTER APP RESTART

Example:

Before closing:

Session:
Work

Tabs:
Google
Gmail
Discord

Active:
Gmail

After reopening:

Work

Google
Gmail
Discord

Gmail should be active.

Where technically possible, restore the actual WebView state rather than only reopening URLs.

---

49. CRASH RECOVERY

If the app crashes or Android kills the process:

On next launch:

Recover saved sessions and tabs.

Mark tabs as needing WebView recreation if required.

Never corrupt the session database because of an interrupted save.

Use atomic/safe persistence where appropriate.

---

50. TESTING

Do not assume the implementation works.

Create a comprehensive test plan.

Test:

Session Isolation

Session A:
Login to Website X as User A

Session B:
Login to Website X as User B

Verify both remain separate.

Tab Sharing

Within Session A:

Tab 1 login

Tab 2 opens same website

Verify login is shared.

Persistence

Close app.

Reopen.

Verify sessions and tabs restore.

Cookies

Clear Session A cookies.

Verify Session B remains logged in.

Storage

Test localStorage/IndexedDB separation.

Navigation

Back
Forward
Reload

File Upload

Upload an image.

Downloads

Download a file.

JavaScript

Test modern JavaScript website.

WebSockets

Test real-time web application.

Popups

Test target="_blank" and window.open.

Permissions

Camera
Microphone
Location

Many Tabs

Test 10–20 tabs where device hardware permits.

Memory Pressure

Switch between many tabs and sessions.

Rotation

Test screen rotation.

Background/Foreground

Send app to background and return.

Android Back Button

Verify correct navigation.

Offline

Open local HTML without internet.

---

51. REAL-WORLD TEST WEBSITES

Use safe, publicly accessible websites for testing.

Test:

- basic HTML site
- JavaScript-heavy SPA
- file upload site
- download site
- WebSocket site
- authentication site
- responsive mobile site

Do not use accounts or credentials belonging to anyone else.

---

52. PERFORMANCE

Optimize:

- WebView creation
- tab switching
- database operations
- session switching
- UI rendering
- memory usage

Do not recreate every WebView unnecessarily.

Do not keep unlimited inactive WebViews alive if this causes memory problems.

Implement sensible memory management.

---

53. LOGGING

Create development logging.

Log:

- session creation
- session switching
- tab creation
- tab closing
- WebView creation/destruction
- navigation errors
- permission requests
- download events

Do not log:

- passwords
- cookies
- authentication tokens
- sensitive personal data

Production builds should reduce verbose logs.

---

54. PRIVACY

The application must not collect browsing data remotely unless explicitly requested.

By default:

- sessions are local
- cookies are local
- history is local
- website storage is local

Do not add analytics/tracking unless explicitly requested.

---

55. ACCESSIBILITY

Support:

- readable text
- sufficient touch target sizes
- screen readers where practical
- meaningful content descriptions
- dark mode
- scalable UI

---

56. ANDROID COMPATIBILITY

Target a modern Android version while maintaining compatibility with older supported Android versions where practical.

Research the current recommended:

- compile SDK
- target SDK
- min SDK

Do not arbitrarily select outdated versions.

Use current stable Android APIs compatible with the HopWeb environment.

---

57. DEPENDENCIES

Keep dependencies minimal.

For every dependency:

- verify compatibility
- verify license
- avoid abandoned libraries
- avoid unnecessary libraries

Prefer Android platform APIs when they are sufficient.

---

58. NO HARDCODED LIMITATIONS

Do not hardcode:

- maximum 3 sessions
- maximum 5 tabs
- specific websites
- specific accounts
- specific domains

Design the system generically.

---

59. IMPORTANT WEBVIEW DETAIL

Research and correctly implement Android WebView profile/data isolation.

Do not assume that simply creating multiple WebView objects creates isolated sessions.

Multiple WebViews can share cookies/storage.

Therefore, implement genuine session separation using the correct WebView-supported mechanisms available for the target Android version.

If the Android WebView API has limitations, document them and design the strongest possible workaround without pretending it provides stronger isolation than it actually does.

---

60. IMPORTANT COOKIE DETAIL

Do not implement fake cookie isolation using JavaScript.

Cookies such as:

- HttpOnly
- Secure
- SameSite

must be managed by the actual WebView cookie/storage system.

The session architecture must account for this.

---

61. IMPORTANT LOGIN DETAIL

Do not save website passwords yourself.

Let websites and WebView authentication mechanisms manage login state.

The app should persist browser session data, not collect credentials.

---

62. UI STATES

Every screen must handle:

- loading
- empty state
- error state
- offline state
- permission state
- deleting state
- restoring state

Do not leave blank screens.

---

63. USER EXPERIENCE

The app should feel fast.

When switching:

Session A → Session B

the transition should be immediate where possible.

When switching:

Tab 1 → Tab 2

do not reload Tab 2 unless necessary.

When a page is loading:

show progress.

When a page finishes:

hide progress.

---

64. SEARCH ENGINE

Allow the user to choose:

Google
Bing
DuckDuckGo
Custom

Store the selection locally.

---

65. CONTEXT MENU

Create a browser-style menu:

New Tab

New Session

Reload

Desktop Site

Share

Open in External Browser

Add Bookmark

History

Downloads

Settings

Clear Site Data

---

66. SHARE

Implement Android share functionality.

Allow sharing:

- current URL
- page title + URL where appropriate

---

67. ORIENTATION

Allow app/project configuration:

Portrait

Landscape

Sensor/Automatic

For websites, use the WebView/page requested orientation only where technically supported and safe.

---

68. SPLIT-SCREEN / MULTITASKING

Where Android allows it, make the application compatible with:

- split screen
- multi-window
- rotation
- background/foreground transitions

Do not break session state during multi-window transitions.

---

69. FUTURE ARCHITECTURE

Design the code so future features can be added:

- extensions
- bookmarks
- password manager integration
- developer tools
- custom DNS
- proxy configuration
- per-session VPN/proxy
- ad blocking
- user scripts
- sync
- cloud backup

Do NOT implement these now unless necessary.

Just ensure the architecture does not prevent future expansion.

---

70. DEVELOPMENT PROCESS

Work in phases.

Phase 1 — Research

Inspect HopWeb capabilities.

Research current Android WebView APIs.

Research session/profile isolation.

Research cookie/storage isolation.

Research APK build limitations.

Produce a technical architecture before coding.

Phase 2 — Foundation

Create the project.

Create navigation.

Create persistence.

Create models.

Create session manager.

Create tab manager.

Phase 3 — WebView

Implement real WebView.

Implement navigation.

Implement JavaScript.

Implement cookies.

Implement storage.

Implement downloads.

Implement file uploads.

Implement permissions.

Phase 4 — Session Isolation

Implement genuine isolated session architecture.

Test multiple accounts.

Test cookie isolation.

Test localStorage isolation.

Phase 5 — Tabs

Implement multi-tab system.

Implement tab switching.

Implement tab persistence.

Implement tab lifecycle.

Phase 6 — UI

Build polished mobile browser interface.

Implement session switcher.

Implement tab overview.

Implement settings.

Phase 7 — Local HTML

Implement HTML/project loading.

Implement local assets.

Implement preview.

Phase 8 — APK/Export

Implement whatever APK/project export capabilities HopWeb supports.

Do not fake unsupported build functionality.

Phase 9 — Testing

Run all tests.

Fix bugs.

Test on real Android if available.

Phase 10 — Production Cleanup

Remove debug code.

Optimize memory.

Review permissions.

Review security.

Review storage.

Review lifecycle.

Prepare final build.

---

71. WORKING METHOD

Do not generate the entire project blindly in one huge response.

Build incrementally.

After each major phase:

1. Implement
2. Inspect code
3. Run/build
4. Test
5. Fix errors
6. Continue

Never knowingly carry an error into the next phase.

If something fails:

- diagnose the root cause
- fix it
- retest

Do not simply suppress errors.

---

72. REQUIREMENT PRIORITY

If requirements conflict, use this priority:

1. Correct session isolation
2. Correct WebView behavior
3. Security
4. Data persistence
5. Stability
6. Performance
7. UX
8. Visual polish

Never sacrifice session isolation for visual simplicity.

---

73. FINAL ACCEPTANCE CRITERIA

The project is NOT complete until all of these are true:

WebView

[ ] Real Android WebView works

[ ] JavaScript works

[ ] Modern websites work

[ ] Navigation works

[ ] File upload works

[ ] Downloads work where supported

[ ] Permissions work

Sessions

[ ] Multiple sessions can exist

[ ] Sessions persist

[ ] Session switching works

[ ] Cookies are isolated

[ ] Login state is isolated

[ ] Local storage is isolated where supported

[ ] Clearing one session does not clear another

Tabs

[ ] Multiple tabs per session

[ ] Tabs persist

[ ] Tabs can be switched

[ ] Tabs can be closed

[ ] Tab state is preserved where possible

[ ] Tabs share the correct session identity

Lifecycle

[ ] App restart restores state

[ ] Background/foreground works

[ ] Rotation works

[ ] Android back button works

[ ] Memory pressure is handled

Local HTML

[ ] HTML can be loaded

[ ] CSS works

[ ] JavaScript works

[ ] Local assets work

Security

[ ] No password collection

[ ] No cookie logging

[ ] No unsafe JS bridge

[ ] Secure storage

[ ] Minimal permissions

Production

[ ] No major crashes

[ ] No known critical bugs

[ ] Build succeeds

[ ] APK can be generated through the supported workflow

[ ] UI is polished

[ ] Code is modular

---

74. FIRST TASK

Do NOT start by generating random UI code.

Your first response should contain:

A. HopWeb Capability Audit

Tell me exactly what HopWeb can and cannot do for this project.

B. Technical Architecture

Show the complete architecture you recommend.

C. Session Isolation Strategy

Explain exactly how you will isolate:

- cookies
- localStorage
- IndexedDB
- cache
- login state
- WebView profiles/data

Do not make assumptions.

D. Project Structure

Show the proposed project/file structure.

E. Development Roadmap

Show all implementation phases.

F. Risks and Limitations

Identify anything that may be technically impossible or restricted by Android/WebView/HopWeb.

G. Then Start Phase 1

After presenting the architecture, begin implementing Phase 1.

Do not skip the architecture.

---

FINAL INSTRUCTION

Treat this as a real production Android application.

Do not build a demo.

Do not build an iframe browser.

Do not fake session isolation.

Do not fake cookies.

Do not fake APK generation.

Do not claim a feature works unless you have actually verified it.

When a feature is limited by Android WebView or HopWeb, explicitly explain the limitation and implement the best technically correct solution available.

The final goal is a polished Android application where users can run websites/HTML inside real WebViews while maintaining multiple independent browser sessions, with multiple tabs inside each session, similar to having multiple separate Chrome profiles running simultaneously.