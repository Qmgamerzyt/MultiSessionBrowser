# BUGFIX_UPDATE_SIGNATURE — "updating always fails; I must uninstall first"

## Symptom
Installing a newer build over an installed one **always** failed, even with a strictly higher
versionCode: the only working procedure was uninstall-old → install-new. Android reported the classic
signature mismatch (package conflicts / "App not installed" — `INSTALL_FAILED_UPDATE_INCOMPATIBLE`).

## Root cause (proven with extracted certificates, 2026-09-24)
`GET /repos/Qmgamerzyt/MultiSessionBrowser/actions/secrets` returned **`[]`** — the repository had **no
signing secrets configured**, so on every CI run:

1. the workflow's conditional "Decode release keystore" step was **skipped** (`if: env.KEYSTORE_BASE64 != ''`),
2. `app/build.gradle.kts` took its silent fallback `signingConfig = … debug`,
3. every fresh GitHub runner auto-generated a **new throwaway `CN=Android Debug` key**.

Android only allows an update to install over an existing app when both APKs carry the **identical**
signer. Since every build had a *different* key, install-over could never succeed between any two builds.

### Extracted signer certificates (openssl on the APK signing blocks / release assets)

| APK | signer subject | cert SHA-256 (first 16 hex) |
|---|---|---|
| v2.0.0 | CN=Android Debug | `6ef1071d87bf55bd` |
| v2.0.1 | CN=Android Debug | `439aaa662a7e7dc3` |
| v2.0.2 (private + public) | CN=Android Debug | `59b0d85ecbfcf718` |
| v2.1.1 (private + public) | CN=Android Debug | `3ecd56b49c9425f4` |
| v2.1.2 `app-arm64-v8a-release.apk` | CN=Android Debug | `63d41fc928a54cf0` |
| v2.1.2 `MultiSessionBrowser-2.1.2-arm64-v8a-release.apk` | CN=Android Debug | `a04a5781999f6b35` |
| v2.1.3 (release + Download copy) | CN=Android Debug | `53a9c9b4aa357a3f` |

Note even the two assets inside the *same* v2.1.2 release carry different keys.

## Fix (2.1.4, versionCode 12)
- **One stable signing identity**: RSA-4096 PKCS12 keystore `release.p12`, alias `release`,
  cert `CN=MultiSessionBrowser Release, O=MultiSessionBrowser`, SHA-256
  `119f0dc91e0adbadb676c598c52234287cf31d6cf2e706e0faf27ebf1610e454`.
  Stored **only** in the Actions secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
  `KEY_PASSWORD`) plus a manual backup in the device's Download folder
  (`MultiSessionBrowser-keystore-BACKUP/`). **Never committed to git.**
- `app/build.gradle.kts`: explicit `storeType = "PKCS12"`; the **debug buildType signs with the same
  key** (so even debug artifacts install over release installs and vice versa). Local builds without
  the env vars keep falling back to the default debug key — dev-only, never shipped.
- `.github/workflows/build-apk.yml`:
  - keystore decode is now **mandatory and runs before any build** — a missing secret fails the run
    instead of silently producing throwaway-key APKs;
  - new **"Verify APK signer"** step runs `apksigner verify --print-certs` on every built APK and fails
    the run unless the certificate equals the committed pin `signing/pinned-signer.sha256`
    (it runs before any artifact upload or release attach).

Result: every APK this CI produces — debug/release, both ABIs — carries the same key, machine-verified
on every build. A future key change cannot ship silently.

## Migration caveat (unavoidable, one time only)
Apps already installed on devices carry dead throwaway keys (their private keys died with the CI
runners that generated them — they exist nowhere anymore). Android provides no override, therefore
**for the 2.1.4 install only: uninstall the old app first**. Every update from 2.1.4 onward installs in
place, permanently enforced by the pin gate.

## How to verify any APK yourself
`apksigner verify --print-certs <apk>` → `Signer #1 certificate SHA-256 digest` must equal the pinned
value above.
