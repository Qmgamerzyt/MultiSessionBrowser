// adblock/background.js - Simple declarativeNetRequest-style blocking
// Note: GeckoView uses webRequest API for blocking

const BLOCK_PATTERNS = [
    "*://*.doubleclick.net/*",
    "*://*.googlesyndication.com/*",
    "*://*.googleadservices.com/*",
    "*://*.adservice.google.com/*",
    "*://*.adnxs.com/*",
    "*://*.adsrvr.org/*",
    "*://*.adform.net/*",
    "*://*.criteo.com/*",
    "*://*.facebook.com/tr*",
    "*://*.facebook.net/*/fbevents.js",
    "*://connect.facebook.net/*",
    "*://*.google-analytics.com/collect*",
    "*://*.googletagmanager.com/*",
    "*://*.hotjar.com/*",
    "*://*.mixpanel.com/*",
    "*://*.segment.com/*",
    "*://*.amplitude.com/*",
    "*://*.fullstory.com/*"
];

browser.webRequest.onBeforeRequest.addListener(
    details => ({ cancel: true }),
    { urls: BLOCK_PATTERNS },
    ["blocking"]
);