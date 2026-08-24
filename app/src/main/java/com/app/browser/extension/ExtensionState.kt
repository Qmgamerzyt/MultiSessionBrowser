package com.app.browser.extension

enum class ExtensionState {
    AUTO,   // Extension runs automatically on page load
    OFF,    // Extension is disabled for this session
    MANUAL  // Extension does NOT auto-run; can be triggered manually
}

enum class ExtensionSource {
    AMO,        // Installed from addons.mozilla.org
    MANUAL_XPI, // Installed from .xpi file/URL
    BUILT_IN    // Bundled with app
}