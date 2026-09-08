# Test plan (run on a real Android 9+ device)

Use only your own accounts / test accounts.

## 0. Build
- [ ] GitHub Action "Build APK" succeeds; artifacts `MultiSessionBrowser-debug` and `-release` exist.
- [ ] APK installs on Android 9 (API 28) and on a recent Android.

## 1. Session isolation (most important)
- [ ] Start page / Sessions sheet shows "Sessions are isolated with separate WebView profiles" (no yellow banner).
- [ ] Session "Personal": log in to Website X as user A.
- [ ] Create session "Work": open Website X → NOT logged in. Log in as user B.
- [ ] Switch back to "Personal" → still user A. Switch to "Work" → still user B.
- [ ] localStorage test: open a page that writes `localStorage` (e.g. a DevTools-free test page) in both sessions → values differ.
- [ ] Sessions → Work → "Clear session data…" (cookies) → Work is logged out, Personal still logged in.
- [ ] Delete "Work" → recreate "Work" → Website X not logged in (profile really deleted).
- [ ] Downloads: an authenticated download in session A succeeds (cookies passed to DownloadManager).

## 2. Tab sharing inside a session
- [ ] Personal tab 1 logged in to X; new tab 2 opens X → already logged in.
- [ ] `target="_blank"` / `window.open` opens a new tab that is still logged in (same profile).

## 3. Persistence & lifecycle
- [ ] Open 3 tabs in Work, make Gmail-like tab active; force-stop app; relaunch → Work restored, same active tab.
- [ ] Rotate device with a video playing → no reload.
- [ ] Toggle dark mode → tabs keep their pages.
- [ ] Background the app for 10 minutes → return, page state intact (or restored from state if frozen).
- [ ] Reboot the phone → sessions, tabs, logins restored.

## 4. Navigation
- [ ] Back / forward / reload / stop; system back goes back in page history, then to opener tab, then
      backgrounds the app (never closes it abruptly).
- [ ] Typing `example.com` → https://example.com; `cats` → search engine; `http://neverssl.com` → loads (cleartext allowed).
- [ ] SPA (pushState) updates the address bar.

## 5. WebView features
- [ ] File upload `<input type=file>` single & multiple; camera capture option appears when CAMERA granted.
- [ ] Download a PDF and an image; download of a `data:` URL; `blob:` download shows the explanation.
- [ ] WebRTC test page: camera + microphone prompt → allow → video shown; deny path works.
- [ ] Geolocation prompt.
- [ ] Fullscreen video (YouTube-like) enters and exits fullscreen.
- [ ] HTTP basic-auth site shows credential dialog.
- [ ] Expired-certificate test site shows SSL warning; "Go back" shows error page; "Proceed" loads.
- [ ] Popup without user gesture is blocked; with gesture opens a tab.
- [ ] `intent://` and `mailto:` links open external apps only after a tap.

## 6. Many tabs & memory
- [ ] Open 20 tabs; the tabs grid marks live tabs with a green dot (≤ "Tabs kept alive").
- [ ] Switch between old tabs → they restore (brief reload) without crashing.
- [ ] `adb shell am send-trim-memory app.multisession.browser RUNNING_CRITICAL` → app survives.

## 7. Local HTML
- [ ] Import a ZIP with index.html + css/ + js/ + images/ → renders with all assets (relative paths OK).
- [ ] Paste HTML → preview. Export ZIP → shareable.
- [ ] Airplane mode → local project still loads.
- [ ] Put a site in assets/www/, build → app starts on it.

## 8. Fallback path (old WebView)
- [ ] On a device with an old System WebView (or after disabling updates): yellow banner appears; clear-data
      dialogs warn that data is shared.

## 9. Security review
- [ ] No `addJavascriptInterface` anywhere (grep). `file://` blocked. Mixed content blocked.
- [ ] Logcat contains no cookies/tokens (grep for `Cookie`).
