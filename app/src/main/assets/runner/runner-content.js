// runner-content.js - Content script that executes JS in page context
// Communicates with background.js via runtime messaging

browser.runtime.onMessage.addListener((message, sender, sendResponse) => {
    if (message.type === "execute_js" && message.jsCode) {
        const requestId = message.requestId;
        
        try {
            // Execute in page context using eval (same as console)
            // For security, we wrap in a function
            const result = (function() {
                return eval(message.jsCode);
            })();
            
            // Handle promises
            if (result instanceof Promise) {
                result.then(
                    resolved => sendResult(requestId, resolved),
                    rejected => sendResult(requestId, null, rejected.message)
                );
            } else {
                sendResult(requestId, result);
            }
        } catch (e) {
            sendResult(requestId, null, e.message);
        }
        
        return true; // async
    }
});

function sendResult(requestId, result, error) {
    try {
        const serializable = serializeResult(result);
        browser.runtime.sendMessage({
            type: "js_result",
            requestId: requestId,
            result: serializable,
            error: error
        });
    } catch (e) {
        browser.runtime.sendMessage({
            type: "js_result",
            requestId: requestId,
            result: "[Unserializable]",
            error: e.message
        });
    }
}

function serializeResult(value) {
    if (value === undefined) return "[undefined]";
    if (value === null) return "[null]";
    if (typeof value === "function") return value.toString();
    if (typeof value === "symbol") return value.toString();
    if (value instanceof Error) return value.toString();
    if (value instanceof HTMLElement) return value.outerHTML;
    if (value instanceof NodeList || value instanceof HTMLCollection) {
        return Array.from(value).map(serializeResult);
    }
    if (Array.isArray(value)) return value.map(serializeResult);
    if (typeof value === "object") {
        try {
            return JSON.stringify(value, (k, v) => {
                if (v instanceof HTMLElement) return v.outerHTML;
                if (v instanceof Node) return v.toString();
                return v;
            }, 2);
        } catch {
            return String(value);
        }
    }
    return String(value);
}