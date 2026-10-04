#!/usr/bin/env python3
"""Build-time patch that makes browser.cookies address GeckoView session contexts.

WHY THIS EXISTS
---------------
MultiSessionBrowser gives every browser session its own isolated cookie jar by
feeding GeckoView a context id (``GeckoSession.Builder.contextId``).  GeckoView
turns that into an ORIGIN ATTRIBUTE - ``geckoViewSessionContextId`` - and
``CookieStorage::GetCookiesFromHost`` looks cookies up with an exact
``CookieKey(baseDomain, originAttributes)`` hash, where ``OriginAttributes``
hashes *and* compares ``mGeckoViewSessionContextId`` (caps/OriginAttributes.h).
So the per-session isolation is structural: a query that does not carry the
right session context id simply finds nothing.

Stock Firefox, however, only ever partitions cookies by ``userContextId`` /
``privateBrowsingId`` / ``firstPartyDomain`` / ``partitionKey``.  On top of
that, Android's ``getContainerForCookieStoreId()`` is a plain ``parseInt``
(TODO bug 1643740), so an extension asking for cookies of one of our tabs gets
storeId ``firefox-container-session-<id>`` -> NaN -> "Invalid cookie store id"
-> ``query()`` returns an empty list for *every* session.  That is why
``cookies.getAll()`` has always come back empty and ``set()``/``remove()``
have always failed.

This script rewrites five Gecko files inside ``assets/omni.ja``:

COOKIE ISOLATION (v2.1.9 - the session context id becomes a first-class
cookie store):

  modules/GeckoViewTab.sys.mjs
      expose ``Tab.sessionContextId`` (the SAFE id, next to the existing
      ``Tab.userContextId`` which exposes the raw one)
  .../parent/ext-toolkit.js
      new store ids ``firefox-gvctx-<safe>`` / ``firefox-gvctxp-<safe>`` and
      the encode/decode helpers used by every ext-*.js caller
  .../parent/ext-cookies.js
      ``convertCookie()`` reports the right storeId, ``oaFromDetails()``
      turns that storeId back into ``geckoViewSessionContextId`` so the
      origin-attribute filter addresses exactly one session's jar

Isolation is preserved, not weakened:
  * nothing is shared - the filter still goes through CookieStorage's exact
    hash lookup, so a wrong/missing context id yields NO cookies rather than
    another session's;
  * ``contextId`` is never dropped and no cookie is ever fabricated;
  * ``cookies``, host permissions and the private-browsing permission gate
    (``context.privateBrowsingAllowed``) are all still enforced by the
    untouched parts of ext-cookies.js.

PAGE ZOOM (v2.1.10 - native full zoom without a page-zoom API):

  modules/GeckoViewNavigation.sys.mjs
      the ``GeckoView:LoadUri`` handler intercepts app-issued
      ``moz-scale:<percent>`` URIs and writes ``browsingContext.fullZoom``
      instead of navigating. GeckoView 155 exposes no zoom API to Java, and
      the previous CSS-zoom javascript: load was blocked by the Content
      Security Policy of sites like Discord; this path never enters the
      content process at all. The URI is only reachable from the app
      (``GeckoSession.Loader.flags(LOAD_FLAGS_BYPASS_LOAD_URI_DELEGATE)``
      approves it before dispatch), the page-initiated counterpart is
      denied in ``TabDelegates.onLoadRequest``, and PageScale clamps the
      percent to 50..200 before it is ever sent.

USAGE
-----
  python3 tools/patch_omni_cookies.py patch --input <stock omni.ja> --output <patched>
  python3 tools/patch_omni_cookies.py verify-source            # committed asset is patched
  python3 tools/patch_omni_cookies.py verify-apk --apk a.apk   # APK carries the patched asset

Regenerate the committed asset whenever ``geckoViewVersion`` in
``app/build.gradle.kts`` changes: the input guard will refuse to patch an
``omni.ja`` it does not recognise.
"""

from __future__ import annotations

import argparse
import hashlib
import sys
import zipfile

# sha256 of ``assets/omni.ja`` inside the GeckoView AAR this patch was authored against.
# Bump together with ``geckoViewVersion`` in app/build.gradle.kts.
SOURCE_GV_VERSION = "155.0.20260903215306"
SOURCE_OMNI_SHA256 = "7c0528e31d2eacd6f56fa61738076ac7db5633b7e7c18118f5237135db0af412"

# Where the patched file is committed and where it is consumed from.
COMMITTED_ASSET = "app/src/main/assets/omni.ja"

# Each entry: (archive path, description, exact original text, replacement text).
PATCHES: list[tuple[str, str, str, str]] = [
    # -----------------------------------------------------------------------------------------
    # 1/3  modules/GeckoViewTab.sys.mjs - expose the SAFE session context id.
    # -----------------------------------------------------------------------------------------
    (
        "modules/GeckoViewTab.sys.mjs",
        "GeckoViewTab: expose the safe sessionContextId",
        """  get userContextId() {
    return this.browser.documentGlobal.moduleManager.settings
      .unsafeSessionContextId;
  }
""",
        """  get userContextId() {
    return this.browser.documentGlobal.moduleManager.settings
      .unsafeSessionContextId;
  }

  // The SAFE session context id GeckoView derives from
  // GeckoSession.Builder.contextId(). It is the very string stored in the
  // geckoViewSessionContextId origin attribute that cookies are keyed on, so
  // an extension needs it to address this session's own cookie jar.
  get sessionContextId() {
    return this.browser.documentGlobal.moduleManager.settings
      .sessionContextId;
  }
""",
    ),
    # -----------------------------------------------------------------------------------------
    # 2/3  ext-toolkit.js - the store id vocabulary.
    # -----------------------------------------------------------------------------------------
    (
        "chrome/toolkit/content/extensions/parent/ext-toolkit.js",
        "ext-toolkit: session-context cookie store ids",
        """global.DEFAULT_STORE = "firefox-default";
global.PRIVATE_STORE = "firefox-private";
global.CONTAINER_STORE = "firefox-container-";

global.getCookieStoreIdForTab = function (data, tab) {
  if (data.incognito) {
    return PRIVATE_STORE;
  }

  if (tab.userContextId) {
    return getCookieStoreIdForContainer(tab.userContextId);
  }

  return DEFAULT_STORE;
};

global.getCookieStoreIdForOriginAttributes = function (originAttributes) {
  if (originAttributes.privateBrowsingId) {
    return PRIVATE_STORE;
  }

  if (originAttributes.userContextId) {
    return getCookieStoreIdForContainer(originAttributes.userContextId);
  }

  return DEFAULT_STORE;
};
""",
        """global.DEFAULT_STORE = "firefox-default";
global.PRIVATE_STORE = "firefox-private";
global.CONTAINER_STORE = "firefox-container-";
// A GeckoView isolated browsing context (GeckoSession.Builder.contextId) is
// tracked by the geckoViewSessionContextId origin attribute, NOT by
// userContextId, and cookies are keyed on that attribute. The cookie store id
// therefore has to carry the session context verbatim: without it every one
// of these sessions looks like the default store - which is empty - and the
// cookies of that session can be neither read nor written.
global.SESSION_CONTEXT_STORE = "firefox-gvctx-";
global.SESSION_CONTEXT_PRIVATE_STORE = "firefox-gvctxp-";

global.getCookieStoreIdForTab = function (data, tab) {
  // The safe id GeckoView derived from contextId(): the very string the
  // cookies of this session carry. Empty when the tab has no isolated context.
  if (tab.sessionContextId) {
    return getCookieStoreIdForSessionContext(
      tab.sessionContextId,
      !!data.incognito
    );
  }

  if (data.incognito) {
    return PRIVATE_STORE;
  }

  if (tab.userContextId) {
    return getCookieStoreIdForContainer(tab.userContextId);
  }

  return DEFAULT_STORE;
};

global.getCookieStoreIdForOriginAttributes = function (originAttributes) {
  if (originAttributes.geckoViewSessionContextId) {
    return getCookieStoreIdForSessionContext(
      originAttributes.geckoViewSessionContextId,
      !!originAttributes.privateBrowsingId
    );
  }

  if (originAttributes.privateBrowsingId) {
    return PRIVATE_STORE;
  }

  if (originAttributes.userContextId) {
    return getCookieStoreIdForContainer(originAttributes.userContextId);
  }

  return DEFAULT_STORE;
};

global.getCookieStoreIdForSessionContext = function (
  sessionContextId,
  isPrivate
) {
  return (
    (isPrivate ? SESSION_CONTEXT_PRIVATE_STORE : SESSION_CONTEXT_STORE) +
    sessionContextId
  );
};

global.getSessionContextForCookieStoreId = function (storeId) {
  if (typeof storeId !== "string") {
    return null;
  }
  if (storeId.startsWith(SESSION_CONTEXT_PRIVATE_STORE)) {
    return {
      sessionContextId: storeId.substring(SESSION_CONTEXT_PRIVATE_STORE.length),
      isPrivate: true,
    };
  }
  if (storeId.startsWith(SESSION_CONTEXT_STORE)) {
    return {
      sessionContextId: storeId.substring(SESSION_CONTEXT_STORE.length),
      isPrivate: false,
    };
  }
  return null;
};

global.isSessionContextCookieStoreId = function (storeId) {
  return getSessionContextForCookieStoreId(storeId) !== null;
};
""",
    ),
    (
        "chrome/toolkit/content/extensions/parent/ext-toolkit.js",
        "ext-toolkit: isValidCookieStoreId accepts session-context stores",
        """global.isValidCookieStoreId = function (storeId) {
  return (
    isDefaultCookieStoreId(storeId) ||
    isPrivateCookieStoreId(storeId) ||
    isContainerCookieStoreId(storeId)
  );
};
""",
        """global.isValidCookieStoreId = function (storeId) {
  return (
    isDefaultCookieStoreId(storeId) ||
    isPrivateCookieStoreId(storeId) ||
    isContainerCookieStoreId(storeId) ||
    isSessionContextCookieStoreId(storeId)
  );
};
""",
    ),
    (
        "chrome/toolkit/content/extensions/parent/ext-toolkit.js",
        "ext-toolkit: OA pattern for a session-context store",
        """  if (isContainerCookieStoreId(cookieStoreId)) {
    let userContextId = getContainerForCookieStoreId(cookieStoreId);
    if (userContextId !== null) {
      return { userContextId };
    }
  }

  throw new ExtensionError("Invalid cookieStoreId");
};
""",
        """  if (isContainerCookieStoreId(cookieStoreId)) {
    let userContextId = getContainerForCookieStoreId(cookieStoreId);
    if (userContextId !== null) {
      return { userContextId };
    }
  }

  let sessionContext = getSessionContextForCookieStoreId(cookieStoreId);
  if (sessionContext) {
    return {
      userContextId: Ci.nsIScriptSecurityManager.DEFAULT_USER_CONTEXT_ID,
      privateBrowsingId: sessionContext.isPrivate ? 1 : 0,
      geckoViewSessionContextId: sessionContext.sessionContextId,
    };
  }

  throw new ExtensionError("Invalid cookieStoreId");
};
""",
    ),
    # -----------------------------------------------------------------------------------------
    # 3/3  ext-cookies.js - read and write the store.
    # -----------------------------------------------------------------------------------------
    (
        "chrome/toolkit/content/extensions/parent/ext-cookies.js",
        "ext-cookies: convertCookie reports the session-context store",
        """  if (cookie.originAttributes.userContextId) {
    result.storeId = getCookieStoreIdForContainer(
      cookie.originAttributes.userContextId
    );
  } else if (cookie.originAttributes.privateBrowsingId || isPrivate) {
    result.storeId = PRIVATE_STORE;
  } else {
    result.storeId = DEFAULT_STORE;
  }
""",
        """  if (cookie.originAttributes.geckoViewSessionContextId) {
    result.storeId = getCookieStoreIdForSessionContext(
      cookie.originAttributes.geckoViewSessionContextId,
      !!(cookie.originAttributes.privateBrowsingId || isPrivate)
    );
  } else if (cookie.originAttributes.userContextId) {
    result.storeId = getCookieStoreIdForContainer(
      cookie.originAttributes.userContextId
    );
  } else if (cookie.originAttributes.privateBrowsingId || isPrivate) {
    result.storeId = PRIVATE_STORE;
  } else {
    result.storeId = DEFAULT_STORE;
  }
""",
    ),
    (
        "chrome/toolkit/content/extensions/parent/ext-cookies.js",
        "ext-cookies: oaFromDetails decodes the session-context store",
        """    } else {
      isPrivate = false;
      let userContextId = getContainerForCookieStoreId(storeId);
      if (!userContextId) {
        throw new ExtensionError(`Invalid cookie store id: "${storeId}"`);
      }
      originAttributes.userContextId = userContextId;
    }
""",
        """    } else {
      isPrivate = false;
      let sessionContext = getSessionContextForCookieStoreId(storeId);
      if (sessionContext) {
        // Isolated GeckoView session context: its cookies carry this same
        // string in geckoViewSessionContextId, so the origin-attribute filter
        // addresses exactly that session's jar and no other one.
        if (sessionContext.isPrivate) {
          isPrivate = true;
        }
        originAttributes.geckoViewSessionContextId =
          sessionContext.sessionContextId;
      } else {
        let userContextId = getContainerForCookieStoreId(storeId);
        if (!userContextId) {
          throw new ExtensionError(`Invalid cookie store id: "${storeId}"`);
        }
        originAttributes.userContextId = userContextId;
      }
    }
""",
    ),
    (
        "chrome/toolkit/content/extensions/parent/ext-cookies.js",
        "ext-cookies: getAllCookieStores marks the private session store private",
        """              incognito: key == PRIVATE_STORE,
""",
        """              incognito:
                key == PRIVATE_STORE ||
                key.startsWith(SESSION_CONTEXT_PRIVATE_STORE),
""",
    ),
    # -----------------------------------------------------------------------------------------
    # 5/5  modules/GeckoViewNavigation.sys.mjs - moz-scale: sets the tab's native zoom.
    # -----------------------------------------------------------------------------------------
    (
        "modules/GeckoViewNavigation.sys.mjs",
        "GeckoViewNavigation: moz-scale URIs set browsingContext.fullZoom",
        """      case "GeckoView:LoadUri": {
        const {
          uri,
          referrerUri,
""",
        """      case "GeckoView:LoadUri": {
        // MultiSessionBrowser (v2.1.10): an app-issued "moz-scale:<percent>"
        // URI asks for this tab's NATIVE page zoom instead of a navigation.
        // It arrives with LOAD_FLAGS_BYPASS_LOAD_URI_DELEGATE (the app
        // approved it in its own NavigationDelegate), never enters the
        // content process - no history entry, no session-state event, and a
        // site's Content-Security-Policy cannot block it - and the percent
        // was clamped to 50..200 by PageScale before dispatch. A page-initiated
        // moz-scale: load never gets here: it lacks the bypass flag and is
        // denied in TabDelegates.onLoadRequest.
        if (typeof aData.uri == "string" && aData.uri.startsWith("moz-scale:")) {
          const pct = parseInt(aData.uri.slice(10), 10);
          if (Number.isInteger(pct) && pct >= 25 && pct <= 500) {
            const bc = this.browser && this.browser.browsingContext;
            if (bc) {
              bc.fullZoom = pct / 100;
            }
          }
          break;
        }
        const {
          uri,
          referrerUri,
""",
    ),
]

# Texts that must be present in a patched file and absent from a stock one.
PATCH_MARKERS = [
    "get sessionContextId()",
    "global.SESSION_CONTEXT_STORE",
    "global.getSessionContextForCookieStoreId",
    "isSessionContextCookieStoreId(storeId)",
    "originAttributes.geckoViewSessionContextId =",
    "cookie.originAttributes.geckoViewSessionContextId",
    "bc.fullZoom = pct / 100",
]


def fail(message: str) -> "NoReturn":  # noqa: F821 - typing aid only
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def sha256_of(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load_archive(path: str) -> tuple[zipfile.ZipFile, dict[str, bytes]]:
    try:
        zf = zipfile.ZipFile(path, "r")
    except (OSError, zipfile.BadZipFile) as exc:  # pragma: no cover - defensive
        fail(f"cannot open {path}: {exc}")
    names = set(zf.namelist())
    missing = sorted({entry for entry, _, _, _ in PATCHES} - names)
    if missing:
        zf.close()
        fail(f"{path} does not contain {', '.join(missing)} - wrong archive?")
    data = {entry: zf.read(entry) for entry, _, _, _ in PATCHES}
    return zf, data


def decode(entry: str, raw: bytes) -> str:
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        fail(f"{entry} is not UTF-8 text")


def apply_patches(originals: dict[str, bytes]) -> dict[str, bytes]:
    """Return a map of archive entry -> fully patched bytes."""
    texts: dict[str, str] = {}
    for entry, _label, _old, _new in PATCHES:
        texts.setdefault(entry, decode(entry, originals[entry]))

    for entry, label, old, new in PATCHES:
        text = texts[entry]
        hits = text.count(old)
        if hits != 1:
            fail(
                f"{label}: anchor in {entry} matched {hits} times (expected 1). "
                f"Gecko changed this file - re-derive the patch against "
                f"GeckoView {SOURCE_GV_VERSION}."
            )
        texts[entry] = text.replace(old, new, 1)

    return {entry: text.encode("utf-8") for entry, text in texts.items()}


def write_archive(source: str, destination: str, replacements: dict[str, bytes]) -> None:
    with zipfile.ZipFile(source, "r") as zin, zipfile.ZipFile(
        destination, "w", compresslevel=9
    ) as zout:
        zout.comment = zin.comment
        for info in zin.infolist():
            payload = replacements.get(info.filename)
            if payload is None:
                payload = zin.read(info.filename)
            out_info = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            out_info.compress_type = info.compress_type
            out_info.external_attr = info.external_attr
            out_info.internal_attr = info.internal_attr
            out_info.create_system = info.create_system
            out_info.comment = info.comment
            zout.writestr(out_info, payload)


def verify_archive_is_patched(path: str, label: str) -> None:
    """Every replacement must be present, plus a handful of unique markers.

    Checking ``new in text`` (rather than ``old not in text``) is deliberate:
    the GeckoViewTab edit appends a getter and keeps the stock one, so its
    anchor legitimately survives the patch.
    """
    with zipfile.ZipFile(path, "r") as zf:
        names = set(zf.namelist())
        for entry, patch_label, _old, new in PATCHES:
            if new not in decode(entry, zf.read(entry)):
                fail(f"{label}: {entry} is not patched ({patch_label})")
        blob = b"".join(
            zf.read(name) for name in names if name.endswith((".js", ".mjs"))
        )
    for marker in PATCH_MARKERS:
        if marker.encode("utf-8") not in blob:
            fail(f"{label}: patched marker missing: {marker}")


def cmd_patch(args: argparse.Namespace) -> int:
    digest = sha256_of(open(args.input, "rb").read())
    if digest != SOURCE_OMNI_SHA256:
        fail(
            f"{args.input} has sha256 {digest}, expected {SOURCE_OMNI_SHA256} "
            f"(GeckoView {SOURCE_GV_VERSION}). Refusing to patch an unknown omni.ja."
        )

    zf, originals = load_archive(args.input)
    replacements = apply_patches(originals)
    zf.close()

    write_archive(args.input, args.output, replacements)

    # Round-trip: every entry we did not touch must be byte identical, and the
    # ones we did must match exactly what we produced.
    touched = {entry for entry, _patch_label, _old, _new in PATCHES}
    with zipfile.ZipFile(args.input, "r") as src, zipfile.ZipFile(args.output, "r") as dst:
        if src.namelist() != dst.namelist():
            fail("entry order changed while rewriting the archive")
        for name in src.namelist():
            want = replacements[name] if name in touched else src.read(name)
            if dst.read(name) != want:
                fail(f"unexpected change to {name} in the patched archive")

    verify_archive_is_patched(args.output, args.output)
    print(f"patched {args.input} -> {args.output}")
    print(f"  entries : {len(replacements)} file(s) rewritten")
    print(f"  sha256  : {sha256_of(open(args.output, 'rb').read())}")
    return 0


def cmd_verify_source(_args: argparse.Namespace) -> int:
    import os

    path = os.environ.get("OMNI_ASSET", COMMITTED_ASSET)
    if not os.path.isfile(path):
        fail(f"{path} is missing - the patched omni.ja must be committed")
    digest = sha256_of(open(path, "rb").read())
    verify_archive_is_patched(path, path)
    print(f"OK source asset {path} is patched (sha256 {digest})")
    return 0


def cmd_verify_apk(args: argparse.Namespace) -> int:
    import os

    reference = os.environ.get("OMNI_ASSET", COMMITTED_ASSET)
    if not os.path.isfile(reference):
        fail(f"{reference} is missing")
    want = sha256_of(open(reference, "rb").read())

    if not args.apk:
        fail("no APKs given")

    failed = False
    for apk in args.apk:
        try:
            with zipfile.ZipFile(apk, "r") as zf:
                packaged = zf.read("assets/omni.ja")
        except (OSError, KeyError, zipfile.BadZipFile) as exc:
            print(f"ERROR: cannot read assets/omni.ja from {apk}: {exc}", file=sys.stderr)
            failed = True
            continue
        got = sha256_of(packaged)
        if got != want:
            print(
                f"ERROR: {apk} packaged a DIFFERENT omni.ja (sha256 {got}), "
                f"expected {want}. The library asset outranked the patched one, "
                f"so browser.cookies would still be broken on device.",
                file=sys.stderr,
            )
            failed = True
        else:
            print(f"OK {os.path.basename(apk)} -> omni.ja {got}")
    return 1 if failed else 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    sub = parser.add_subparsers(dest="command", required=True)

    p_patch = sub.add_parser("patch", help="rewrite a stock omni.ja")
    p_patch.add_argument("--input", required=True)
    p_patch.add_argument("--output", required=True)
    p_patch.set_defaults(func=cmd_patch)

    p_src = sub.add_parser("verify-source", help="committed asset carries the patch")
    p_src.set_defaults(func=cmd_verify_source)

    p_apk = sub.add_parser("verify-apk", help="built APKs carry the committed asset")
    p_apk.add_argument("--apk", nargs="+", required=True)
    p_apk.set_defaults(func=cmd_verify_apk)

    args = parser.parse_args(argv)
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
