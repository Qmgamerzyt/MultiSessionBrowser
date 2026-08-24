// background.js - Native messaging bridge for the runner extension
// This handles messages from the native side to execute JS in tabs

let pendingRequests = new Map();
let nextRequestId = 0;

// Listen for messages from native code (GeckoView)
browser.runtime.onMessageExternal.addListener((message, sender, sendResponse) => {
    if (message.type === "run_js" && message.jsCode) {
        const requestId = ++nextRequestId;
        pendingRequests.set(requestId, sendResponse);
        
        // Forward to content script in the active tab
        browser.tabs.query({ active: true, currentWindow: true }).then(tabs => {
            if (tabs[0]) {
                browser.tabs.sendMessage(tabs[0].id, {
                    type: "execute_js",
                    requestId: requestId,
                    jsCode: message.jsCode
                }).catch(err => {
                    pendingRequests.delete(requestId);
                    sendResponse({ error: err.message });
                });
            } else {
                pendingRequests.delete(requestId);
                sendResponse({ error: "No active tab" });
            }
        });
        return true; // async response
    }
    
    if (message.type === "run_manual_script" && message.scriptCode) {
        const requestId = ++nextRequestId;
        pendingRequests.set(requestId, sendResponse);
        
        browser.tabs.query({ active: true, currentWindow: true }).then(tabs => {
            if (tabs[0]) {
                browser.tabs.sendMessage(tabs[0].id, {
                    type: "execute_js",
                    requestId: requestId,
                    jsCode: message.scriptCode
                }).catch(err => {
                    pendingRequests.delete(requestId);
                    sendResponse({ error: err.message });
                });
            } else {
                pendingRequests.delete(requestId);
                sendResponse({ error: "No active tab" });
            }
        });
        return true;
    }
});

// Listen for results from content script
browser.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message.type === "js_result" && message.requestId) {
        const callback = pendingRequests.get(message.requestId);
        if (callback) {
            pendingRequests.delete(message.requestId);
            callback({ result: message.result, error: message.error });
        }
    }
});

// Also support native messaging API for MANUAL mode
browser.runtime.onConnectNative.addListener(port => {
    port.onMessage.addListener(msg => {
        if (msg.type === "run_manual" && msg.scriptCode) {
            browser.tabs.query({ active: true, currentWindow: true }).then(tabs => {
                if (tabs[0]) {
                    browser.tabs.sendMessage(tabs[0].id, {
                        type: "execute_js",
                        requestId: msg.requestId || Date.now(),
                        jsCode: msg.scriptCode
                    });
                }
            });
        }
    });
});