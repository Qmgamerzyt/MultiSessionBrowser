# BUG FIX FILE — CI Failure: GitHub Actions Artifact Storage Quota

**Date:** 2026-09-24
**Run:** https://github.com/Qmgamerzyt/MultiSessionBrowser/actions/runs/35924129995
**Commit:** `111618deeca38d3fd295aef8a3a5ff6db089f4b6`
**versionName:** 2.1.2 / **versionCode:** 10
**Failing step:** `Upload debug APKs` (step 11 of 15)

---

## 1. FULL ERROR LOG (verbatim from CI)

```
2026-09-23T21:44:16.7327205Z ##[group]Run actions/upload-artifact@v4
2026-09-23T21:44:16.7327540Z with:
2026-09-23T21:44:16.7327767Z   name: MultiSessionBrowser-2.1.2-debug
2026-09-23T21:44:16.7328054Z   path: app/build/outputs/apk/debug/*.apk
2026-09-23T21:44:16.7328329Z   if-no-files-found: error
2026-09-23T21:44:16.7328568Z   compression-level: 6
2026-09-23T21:44:16.7328796Z   overwrite: false
2026-09-23T21:44:16.7329021Z   include-hidden-files: false
2026-09-23T21:44:16.7329283Z env:
2026-09-23T21:44:16.7329478Z   KEYSTORE_BASE64:
2026-09-23T21:44:16.7329784Z   JAVA_HOME: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-23T21:44:16.7330252Z   JAVA_HOME_17_X64: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-23T21:44:16.7330628Z   ANDROID_HOME: /usr/local/lib/android/sdk
2026-09-23T21:44:16.7330902Z   ANDROID_SDK_ROOT: /usr/local/lib/android/sdk
2026-09-23T21:44:16.7331916Z   DEVELOCITY_INJECTION_CUSTOM_VALUE: gradle-actions
2026-09-23T21:44:16.7332630Z   GITHUB_DEPENDENCY_GRAPH_ENABLED: false
2026-09-23T21:44:16.7332902Z ##[endgroup]
2026-09-23T21:44:16.8939668Z (node:2402) [DEP0040] DeprecationWarning: The `punycode` module is deprecated. Please use a userland alternative instead.
2026-09-23T21:44:16.9010325Z With the provided path, there will be 2 files uploaded
2026-09-23T21:44:16.9016423Z Artifact name is valid!
2026-09-23T21:44:16.9017790Z Root directory input is valid!
2026-09-23T21:44:17.0988075Z ##[error]Failed to CreateArtifact: Artifact storage quota has been hit. Unable to upload any new artifacts. Usage is recalculated every 6-12 hours.
More info on storage limits: https://docs.github.com/en/billing/managing-billing-for-github-actions/about-billing-for-github-actions#calculating-minute-and-storage-spending
```

### Full job step status (run 35924129995)

```
Set up job:                                    success
Checkout:                                      success
Verify source tree:                            success
Set up JDK 17:                                 success
Accept SDK licenses:                           success
Install SDK platforms + build-tools:           success
Print resolved toolchain (diagnostics):        success
Setup Gradle:                                  success
Build debug APKs:                              success   <-- build WORKS
List built APKs:                               success   <-- 2 APKs exist, correct names
Upload debug APKs:                             FAILURE   <-- dies HERE (CreateArtifact quota)
Decode release keystore:                       skipped
Build release APKs:                            skipped
Upload release APKs:                           skipped
Attach APKs to GitHub Release:                 skipped
```

**Key fact: the build itself is 100% healthy.** Checkout → SDK → Gradle →
`assembleDebug` → 2 correctly-named APK files on disk. The ONLY failure is
GitHub refusing to *store* the artifact because the account's artifact
storage counter says "over quota".

---

## 2. WHY THIS HAPPENED (root cause)

### Direct cause
`actions/upload-artifact@v4` calls GitHub's artifact CreateArtifact API.
The API rejects the request with a **quota error** because the account's
*calculated* artifact storage usage exceeds the free-plan limit.

### Why the account is over quota
1. The workflow uploads **2 large artifacts per run** (debug ~191MB +
   release ~188MB — GeckoView APKs are big).
2. Runs happened many times over Sep 8 → Sep 23 (CI-fix iterations), so
   artifacts accumulated:
   - **MultiSessionBrowser: 47 artifacts ≈ 4.0 GB**
   - **multisection-browser: 6 artifacts ≈ 936 MB**
   - **fullscreen-browser: 4 artifacts ≈ 14 MB**
   - **Minecraft-Over-enchantment-generator: 8 artifacts ≈ 0 MB**
   - **Total: 65 artifacts ≈ 5 GB**
3. Free GitHub accounts include only **500 MB** of Actions artifact
   storage. 5 GB ≫ 500 MB ⇒ every new upload is rejected.

### Why it STILL fails after deleting everything
All 65 artifacts were deleted via the API (verified: **0 artifacts, 0
packages account-wide** across all 21 repos and all package types —
container/npm/maven/nuget/rubygems/docker all empty). Yet the very next
run (and the run 24h later) still gets the quota error.

GitHub's own error message explains it:

> *"Usage is recalculated every 6-12 hours."*

The quota verdict shown to `upload-artifact` comes from a **cached billing
snapshot**, not from a live count. Deleting artifacts does NOT immediately
update that snapshot. Two consequences:

- **a)** The counter lags up to 6-12 h (observed: still wrong after ~9 h
  on 2026-09-23, and again on 2026-09-24 — GitHub has now been showing
  the stale "over quota" verdict for >24 h after the actual data was
  removed).
- **b)** There is a second, compounding factor: **`cancel-in-progress: true`
  + repeated re-triggers may have left in-flight/pending artifact
  records**, and interrupted uploads are known to occasionally keep
  counting until the next recalculation cycle.

### What was already ruled out
- ❌ Not a token problem — new token `ghp_dsy...J0EjXTz` behaves identically.
- ❌ Not a repo-local artifact problem — repo verified empty.
- ❌ Not leftover artifacts — 0 across all 21 repos.
- ❌ Not packages — 0 in every package type.
- ❌ Not a build/source problem — every pre-upload step passes.
- ❌ Not the workflow YAML — the same YAML built successfully on
  2026-09-21/22 when quota was momentarily available (APKs were produced
  then; only upload failed).

---

## 3. WHAT TO CHANGE TO FIX THIS

### Fix A — Stop the bleeding in the workflow (REQUIRED, do this first)
The workflow must not treat artifact upload as a hard dependency, and must
stop accumulating 380 MB/run.

In `.github/workflows/build-apk.yml`:

1. **Add `retention-days: 1` to both upload steps** (lines 97-102 and
   119-124). Artifacts auto-delete after 1 day ⇒ the 500 MB pool can
   never fill up again:

   ```yaml
   - name: Upload debug APKs
     uses: actions/upload-artifact@v4
     with:
       name: MultiSessionBrowser-${{ steps.version.outputs.version_name }}-debug
       path: app/build/outputs/apk/debug/*.apk
       if-no-files-found: error
       retention-days: 1        # <-- ADD
   ```

   (same `retention-days: 1` for the release upload step)

2. **Optionally make uploads non-fatal** with `continue-on-error: true` on
   the *debug* upload only, so that even if GitHub's quota verdict is
   stale, the job continues to `Build release APKs` → `Upload release
   APKs` → `Attach APKs to GitHub Release`. The **release-asset** upload
   (`softprops/action-gh-release`) uses a *different* storage pool (Git
   release assets), which is NOT affected by the Actions artifact quota —
   so tagged releases would still publish APKs even while the artifact
   counter is stale.

### Fix B — Clear GitHub's stale quota verdict (REQUIRED, account-side)
Deletion is done; the counter must now be *forced to refresh*:

1. Wait for the next recalculation window — GitHub docs say 6-12 h, but
   observed behavior after mass deletion can take **up to 24-48 h**.
2. Verify the live number at
   https://github.com/settings/billing → **Billing & plans → Usage →
   GitHub Actions → Storage**. It must show **0 GB / 500 MB** (or close).
   The CI error will keep coming back until THAT page shows under quota —
   the Actions UI and the API read the same cached value, so checking the
   billing page is the reliable oracle.
3. If the billing page STILL shows several GB after 48 h with 0 artifacts
   visible, the counter is stuck (a known GitHub-side drift after bulk
   deletion). Remedies, in order:
   - Open a support ticket at https://support.github.com/contact (subject:
     "Actions artifact storage usage not recalculated after artifact
     deletion", include: account has 0 artifacts across all repos since
     2026-09-23, usage still shows over quota) — GitHub support can zero
     the counter manually.
   - Or upgrade to GitHub Pro ($4/month, 2 GB artifact storage) purely to
     unblock, then still open the ticket to fix the phantom usage.

### Fix C — Prevent recurrence (RECOMMENDED)
- Keep `retention-days: 1` (Fix A.1) permanently.
- Consider **not uploading debug APKs as artifacts at all** (only release),
  halving per-run usage.
- Old runs' artifacts can be bulk-deleted from the Actions UI, or set
  repo **Settings → Actions → Artifact and log retention** to 1 day.

---

## 4. DECISION NEEDED

Per the standing instruction ("on CI failure: document, do NOT self-fix"),
Fix A (workflow YAML changes) has **not** been applied. Reply with:

- **"apply fix A"** — I add `retention-days: 1` (+ optional
  `continue-on-error` on debug upload), push, re-run.
- **"wait"** — re-trigger later after the billing page shows quota reset.

The build pipeline itself needs **no code changes** — it compiles
versionName 2.1.2 / versionCode 10 successfully every run.
