# BUGFIX_CI_213_LOG.md — v2.1.3 CI failure #1: invalid `layout_gravity` value

## Failure

* Run: `35949052778` (commit `d1ef99e`, trigger: push to `main`)
* Job: `Build debug + release APK (GeckoView, arm64-v8a + armeabi-v7a)` (job id `107473401827`)
* Failed step: **Build debug APKs** (Gradle `assembleDebug` → resource linking)

## Cause (exact throwing frame — AAPT2 resource linking, single error)

The fullscreen nub added in `activity_browser.xml` for the new HUD feature used

```xml
android:layout_gravity="center_end"
```

`center_end` is **not a valid `layout_gravity` flag token**. AAPT2 validates this attribute
against its flag table, which only accepts the single tokens (or `|`-separated compounds of):
`bottom, center, center_horizontal, center_vertical, clip_horizontal, clip_vertical, end, fill,
fill_horizontal, fill_vertical, left, right, start, top`. `center` (17) and `end` (8388613) must
be combined explicitly as `center_vertical|end`. This fails at *resource linking*
(`failed linking file resources`) before APK assembly.

Local validation (`python3` XML parse) cannot catch this — XML well-formedness does not imply
attribute-enum validity; only AAPT2 does. That is exactly what CI caught.

## Fix (Fix A — applied immediately instead of waiting, per authorization)

`app/src/main/res/layout/activity_browser.xml`, nub element:

```diff
- android:layout_gravity="center_end"
+ android:layout_gravity="center_vertical|end"
```

`center_vertical|end` is proven valid in this very file (`layout_gravity="bottom|end"` on the HUD
include) and keeps the intended placement (right edge, vertically centered); the nub's drag clamps
in `HudController.makeNubDraggable` are position-agnostic and need no change.

## Full log of the failed run (job 107473401827, verbatim)

```text
﻿2026-09-24T02:52:12.5380610Z Current runner version: '2.337.0'
2026-09-24T02:52:12.5405554Z ##[group]Runner Image Provisioner
2026-09-24T02:52:12.5406423Z Hosted Compute Agent
2026-09-24T02:52:12.5406918Z Version: 20260828.587
2026-09-24T02:52:12.5407475Z Commit: abac92662cab4cc7352de4f9f9d2e2419aad9c29
2026-09-24T02:52:12.5408102Z Build Date: 2026-08-28T16:44:25Z
2026-09-24T02:52:12.5408668Z Worker ID: {d111aa10-29a5-4b02-8da7-fdfb255d442d}
2026-09-24T02:52:12.5409305Z Azure Region: mexicocentral
2026-09-24T02:52:12.5409880Z ##[endgroup]
2026-09-24T02:52:12.5410992Z ##[group]Operating System
2026-09-24T02:52:12.5411573Z Ubuntu
2026-09-24T02:52:12.5412018Z 24.04.5
2026-09-24T02:52:12.5412554Z LTS
2026-09-24T02:52:12.5412992Z ##[endgroup]
2026-09-24T02:52:12.5413452Z ##[group]Runner Image
2026-09-24T02:52:12.5414170Z Image: ubuntu-24.04
2026-09-24T02:52:12.5414652Z Version: 20260920.314.1
2026-09-24T02:52:12.5415781Z Included Software: https://github.com/actions/runner-images/blob/ubuntu24/20260920.314/images/ubuntu/Ubuntu2404-Readme.md
2026-09-24T02:52:12.5417030Z Image Release: https://github.com/actions/runner-images/releases/tag/ubuntu24%2F20260920.314
2026-09-24T02:52:12.5417898Z ##[endgroup]
2026-09-24T02:52:12.5418809Z ##[group]GITHUB_TOKEN Permissions
2026-09-24T02:52:12.5420514Z Contents: write
2026-09-24T02:52:12.5421014Z Metadata: read
2026-09-24T02:52:12.5421535Z ##[endgroup]
2026-09-24T02:52:12.5423306Z Secret source: Actions
2026-09-24T02:52:12.5424532Z Cache mode: write
2026-09-24T02:52:12.5425145Z Prepare workflow directory
2026-09-24T02:52:12.5880796Z Prepare all required actions
2026-09-24T02:52:12.5920675Z Getting action download info
2026-09-24T02:52:12.8966763Z Download action repository 'actions/checkout@v4' (SHA:11d5960a326750d5838078e36cf38b85af677262)
2026-09-24T02:52:13.0268501Z Download action repository 'actions/setup-java@v4' (SHA:cf277c60eb25467037889841efdb72551f06f6c3)
2026-09-24T02:52:14.1251339Z Download action repository 'gradle/actions@v4' (SHA:ed408507eac070d1f99cc633dbcf757c94c7933a)
2026-09-24T02:52:19.1385023Z Download action repository 'actions/upload-artifact@v4' (SHA:ea165f8d65b6e75b540449e92b4886f43607fa02)
2026-09-24T02:52:20.6623687Z Download action repository 'softprops/action-gh-release@v3' (SHA:efb35369e0ad2afab669f228072c1b0d510eae64)
2026-09-24T02:52:21.2622033Z Complete job name: Build debug + release APK (GeckoView, arm64-v8a + armeabi-v7a)
2026-09-24T02:52:21.3230440Z ##[group]Run actions/checkout@v4
2026-09-24T02:52:21.3231160Z with:
2026-09-24T02:52:21.3231378Z   repository: Qmgamerzyt/MultiSessionBrowser
2026-09-24T02:52:21.3233479Z   token: ***
2026-09-24T02:52:21.3233669Z   ssh-strict: true
2026-09-24T02:52:21.3234122Z   ssh-user: git
2026-09-24T02:52:21.3234314Z   persist-credentials: true
2026-09-24T02:52:21.3234537Z   clean: true
2026-09-24T02:52:21.3234732Z   sparse-checkout-cone-mode: true
2026-09-24T02:52:21.3234946Z   fetch-depth: 1
2026-09-24T02:52:21.3235136Z   fetch-tags: false
2026-09-24T02:52:21.3235312Z   show-progress: true
2026-09-24T02:52:21.3235504Z   lfs: false
2026-09-24T02:52:21.3235697Z   submodules: false
2026-09-24T02:52:21.3235887Z   set-safe-directory: true
2026-09-24T02:52:21.3236101Z   allow-unsafe-pr-checkout: false
2026-09-24T02:52:21.3236586Z env:
2026-09-24T02:52:21.3236757Z   KEYSTORE_BASE64: 
2026-09-24T02:52:21.3236946Z ##[endgroup]
2026-09-24T02:52:21.4331902Z Syncing repository: Qmgamerzyt/MultiSessionBrowser
2026-09-24T02:52:21.4334901Z ##[group]Getting Git version info
2026-09-24T02:52:21.4335592Z Working directory is '/home/runner/work/MultiSessionBrowser/MultiSessionBrowser'
2026-09-24T02:52:21.4337003Z [command]/usr/bin/git version
2026-09-24T02:52:21.5116165Z git version 2.55.0
2026-09-24T02:52:21.5144167Z ##[endgroup]
2026-09-24T02:52:21.5197008Z Temporarily overriding HOME='/home/runner/work/_temp/e36ea52b-c94d-4ac0-bc5e-cc678569a5e7' before making global git config changes
2026-09-24T02:52:21.5198395Z Adding repository directory to the temporary git global config as a safe directory
2026-09-24T02:52:21.5220336Z [command]/usr/bin/git config --global --add safe.directory /home/runner/work/MultiSessionBrowser/MultiSessionBrowser
2026-09-24T02:52:21.5283062Z Deleting the contents of '/home/runner/work/MultiSessionBrowser/MultiSessionBrowser'
2026-09-24T02:52:21.5284102Z ##[group]Initializing the repository
2026-09-24T02:52:21.5286025Z [command]/usr/bin/git init /home/runner/work/MultiSessionBrowser/MultiSessionBrowser
2026-09-24T02:52:21.5399753Z hint: Using 'master' as the name for the initial branch. This default branch name
2026-09-24T02:52:21.5400218Z hint: will change to "main" in Git 3.0. To configure the initial branch name
2026-09-24T02:52:21.5400795Z hint: to use in all of your new repositories, which will suppress this warning,
2026-09-24T02:52:21.5401290Z hint: call:
2026-09-24T02:52:21.5401536Z hint:
2026-09-24T02:52:21.5401880Z hint: 	git config --global init.defaultBranch <name>
2026-09-24T02:52:21.5402285Z hint:
2026-09-24T02:52:21.5402668Z hint: Names commonly chosen instead of 'master' are 'main', 'trunk' and
2026-09-24T02:52:21.5403313Z hint: 'development'. The just-created branch can be renamed via this command:
2026-09-24T02:52:21.5403981Z hint:
2026-09-24T02:52:21.5404231Z hint: 	git branch -m <name>
2026-09-24T02:52:21.5404526Z hint:
2026-09-24T02:52:21.5404954Z hint: Disable this message with "git config set advice.defaultBranchName false"
2026-09-24T02:52:21.5413494Z Initialized empty Git repository in /home/runner/work/MultiSessionBrowser/MultiSessionBrowser/.git/
2026-09-24T02:52:21.5419341Z [command]/usr/bin/git remote add origin https://github.com/Qmgamerzyt/MultiSessionBrowser
2026-09-24T02:52:21.5474644Z ##[endgroup]
2026-09-24T02:52:21.5475179Z ##[group]Disabling automatic garbage collection
2026-09-24T02:52:21.5475657Z [command]/usr/bin/git config --local gc.auto 0
2026-09-24T02:52:21.5506998Z ##[endgroup]
2026-09-24T02:52:21.5507507Z ##[group]Setting up auth
2026-09-24T02:52:21.5512568Z [command]/usr/bin/git config --local --name-only --get-regexp core\.sshCommand
2026-09-24T02:52:21.5547123Z [command]/usr/bin/git submodule foreach --recursive sh -c "git config --local --name-only --get-regexp 'core\.sshCommand' && git config --local --unset-all 'core.sshCommand' || :"
2026-09-24T02:52:21.7388101Z [command]/usr/bin/git config --local --name-only --get-regexp http\.https\:\/\/github\.com\/\.extraheader
2026-09-24T02:52:21.7390860Z [command]/usr/bin/git submodule foreach --recursive sh -c "git config --local --name-only --get-regexp 'http\.https\:\/\/github\.com\/\.extraheader' && git config --local --unset-all 'http.https://github.com/.extraheader' || :"
2026-09-24T02:52:21.7393204Z [command]/usr/bin/git config --local --name-only --get-regexp ^includeIf\.gitdir:
2026-09-24T02:52:21.7395174Z [command]/usr/bin/git submodule foreach --recursive git config --local --show-origin --name-only --get-regexp remote.origin.url
2026-09-24T02:52:21.7398962Z [command]/usr/bin/git config --local http.https://github.com/.extraheader AUTHORIZATION: basic ***
2026-09-24T02:52:21.7439206Z ##[endgroup]
2026-09-24T02:52:21.7440181Z ##[group]Fetching the repository
2026-09-24T02:52:21.7448908Z [command]/usr/bin/git -c protocol.version=2 fetch --no-tags --prune --no-recurse-submodules --depth=1 origin +d1ef99e61703ec4af135158340a820e9eadd24ff:refs/remotes/origin/main
2026-09-24T02:52:22.5779057Z From https://github.com/Qmgamerzyt/MultiSessionBrowser
2026-09-24T02:52:22.5780178Z  * [new ref]         d1ef99e61703ec4af135158340a820e9eadd24ff -> origin/main
2026-09-24T02:52:22.5782275Z ##[endgroup]
2026-09-24T02:52:22.5782911Z ##[group]Determining the checkout info
2026-09-24T02:52:22.5785443Z ##[endgroup]
2026-09-24T02:52:22.5790620Z [command]/usr/bin/git sparse-checkout disable
2026-09-24T02:52:22.5844728Z [command]/usr/bin/git config --local --unset-all extensions.worktreeConfig
2026-09-24T02:52:22.5878692Z ##[group]Checking out the ref
2026-09-24T02:52:22.5882811Z [command]/usr/bin/git checkout --progress --force -B main refs/remotes/origin/main
2026-09-24T02:52:22.6021626Z Switched to a new branch 'main'
2026-09-24T02:52:22.6026503Z branch 'main' set up to track 'origin/main'.
2026-09-24T02:52:22.6035890Z ##[endgroup]
2026-09-24T02:52:22.6080484Z [command]/usr/bin/git log -1 --format=%H
2026-09-24T02:52:22.6107589Z d1ef99e61703ec4af135158340a820e9eadd24ff
2026-09-24T02:52:22.6303481Z ##[group]Run set -euo pipefail
2026-09-24T02:52:22.6303978Z set -euo pipefail
2026-09-24T02:52:22.6304482Z test -f settings.gradle.kts && test -f app/build.gradle.kts || { echo "::error::Gradle project not found at the repository root"; exit 1; }
2026-09-24T02:52:22.6305328Z test -f app/src/main/java/app/multisession/browser/tabs/TabManager.kt || { echo "::error::Current source tree missing (TabManager.kt) - stale project copy?"; exit 1; }
2026-09-24T02:52:22.6305951Z VN=$(grep -oP 'versionName = "\K[^"]+' app/build.gradle.kts)
2026-09-24T02:52:22.6306295Z VC=$(grep -oP 'versionCode = \K[0-9]+' app/build.gradle.kts)
2026-09-24T02:52:22.6306663Z echo "Source versionName=$VN versionCode=$VC (commit ${GITHUB_SHA::8})"
2026-09-24T02:52:22.6307152Z # The last public release was 2.0.0 (versionCode < 10). Anything at or below it is a stale checkout.
2026-09-24T02:52:22.6307745Z if [ "$VC" -lt 10 ]; then echo "::error::versionCode $VC belongs to an old release, refusing to build/publish it"; exit 1; fi
2026-09-24T02:52:22.6308198Z if [[ "$GITHUB_REF" == refs/tags/v* ]]; then
2026-09-24T02:52:22.6308465Z   TAG="${GITHUB_REF#refs/tags/v}"
2026-09-24T02:52:22.6308875Z   if [ "$TAG" != "$VN" ]; then echo "::error::Tag v$TAG does not match versionName $VN in app/build.gradle.kts"; exit 1; fi
2026-09-24T02:52:22.6309269Z fi
2026-09-24T02:52:22.6309704Z if grep -rq "android.webkit.WebView" app/src/main/java; then echo "::error::WebView reference found - engine must stay GeckoView"; exit 1; fi
2026-09-24T02:52:22.6310218Z echo "version_name=$VN" >> "$GITHUB_OUTPUT"
2026-09-24T02:52:22.6310526Z echo "version_code=$VC" >> "$GITHUB_OUTPUT"
2026-09-24T02:52:22.6820420Z shell: /usr/bin/bash --noprofile --norc -e -o pipefail {0}
2026-09-24T02:52:22.6820791Z env:
2026-09-24T02:52:22.6820975Z   KEYSTORE_BASE64: 
2026-09-24T02:52:22.6821206Z ##[endgroup]
2026-09-24T02:52:22.6948400Z Source versionName=2.1.3 versionCode=11 (commit d1ef99e6)
2026-09-24T02:52:22.7086665Z ##[group]Run actions/setup-java@v4
2026-09-24T02:52:22.7086927Z with:
2026-09-24T02:52:22.7087114Z   distribution: temurin
2026-09-24T02:52:22.7087318Z   java-version: 17
2026-09-24T02:52:22.7087507Z   java-package: jdk
2026-09-24T02:52:22.7087695Z   check-latest: false
2026-09-24T02:52:22.7087921Z   server-id: github
2026-09-24T02:52:22.7088106Z   server-username: GITHUB_ACTOR
2026-09-24T02:52:22.7088326Z   server-password: GITHUB_TOKEN
2026-09-24T02:52:22.7088552Z   overwrite-settings: true
2026-09-24T02:52:22.7088753Z   job-status: success
2026-09-24T02:52:22.7090718Z   token: ***
2026-09-24T02:52:22.7090896Z env:
2026-09-24T02:52:22.7091062Z   KEYSTORE_BASE64: 
2026-09-24T02:52:22.7091269Z ##[endgroup]
2026-09-24T02:52:22.8519381Z ##[warning]setup-java v4 is deprecated and will no longer receive updates. Please migrate to actions/setup-java@v5.
2026-09-24T02:52:22.8528536Z ##[group]Installed distributions
2026-09-24T02:52:22.8664259Z (node:1951) [DEP0040] DeprecationWarning: The `punycode` module is deprecated. Please use a userland alternative instead.
2026-09-24T02:52:22.8665068Z Resolved Java 17.0.20+1 from tool-cache
2026-09-24T02:52:22.8665607Z (Use `node --trace-deprecation ...` to show where the warning was created)
2026-09-24T02:52:22.8666375Z Setting Java 17.0.20+1 as the default
2026-09-24T02:52:22.8666818Z Creating toolchains.xml for JDK version 17 from temurin
2026-09-24T02:52:22.8724397Z Writing to /home/runner/.m2/toolchains.xml
2026-09-24T02:52:22.8724977Z 
2026-09-24T02:52:22.8725437Z Java configuration:
2026-09-24T02:52:22.8726027Z   Distribution: temurin
2026-09-24T02:52:22.8726551Z   Version: 17.0.20+1
2026-09-24T02:52:22.8727188Z   Path: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-24T02:52:22.8728177Z 
2026-09-24T02:52:22.8728847Z ##[endgroup]
2026-09-24T02:52:22.8746231Z Creating settings.xml with server-id: github
2026-09-24T02:52:22.8763125Z Writing to /home/runner/.m2/settings.xml
2026-09-24T02:52:22.8997786Z ##[group]Run yes | /usr/local/lib/android/sdk/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null 2>&1 || true
2026-09-24T02:52:22.8998569Z yes | /usr/local/lib/android/sdk/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null 2>&1 || true
2026-09-24T02:52:22.9088129Z shell: /usr/bin/bash -e {0}
2026-09-24T02:52:22.9088540Z env:
2026-09-24T02:52:22.9088809Z   KEYSTORE_BASE64: 
2026-09-24T02:52:22.9089251Z   JAVA_HOME: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-24T02:52:22.9089998Z   JAVA_HOME_17_X64: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-24T02:52:22.9090654Z ##[endgroup]
2026-09-24T02:52:29.9847512Z yes: standard output: Broken pipe
2026-09-24T02:52:29.9917146Z ##[group]Run /usr/local/lib/android/sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-37.1" "build-tools;36.0.0" "platform-tools"
2026-09-24T02:52:29.9917917Z /usr/local/lib/android/sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-37.1" "build-tools;36.0.0" "platform-tools"
2026-09-24T02:52:29.9973331Z shell: /usr/bin/bash -e {0}
2026-09-24T02:52:29.9973554Z env:
2026-09-24T02:52:29.9973914Z   KEYSTORE_BASE64: 
2026-09-24T02:52:29.9974231Z   JAVA_HOME: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-24T02:52:29.9974624Z   JAVA_HOME_17_X64: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-24T02:52:29.9974943Z ##[endgroup]
2026-09-24T02:52:30.8272034Z Loading package information...                                                  
2026-09-24T02:52:30.9562391Z Loading local repository...                                                     
2026-09-24T02:52:30.9563555Z [                                       ] 3% Loading local repository...        
2026-09-24T02:52:30.9610611Z [                                       ] 3% Fetch remote repository...         
2026-09-24T02:52:31.3309823Z [=                                      ] 3% Fetch remote repository...         
2026-09-24T02:52:31.4537793Z [=                                      ] 4% Fetch remote repository...         
2026-09-24T02:52:31.4813462Z [=                                      ] 5% Fetch remote repository...         
2026-09-24T02:52:31.5336930Z [==                                     ] 5% Fetch remote repository...         
2026-09-24T02:52:31.6569452Z [==                                     ] 6% Fetch remote repository...         
2026-09-24T02:52:31.7351310Z [==                                     ] 7% Fetch remote repository...         
2026-09-24T02:52:31.7360023Z [==                                     ] 7% Computing updates...               
2026-09-24T02:52:31.7379294Z [===                                    ] 8% Computing updates...               
2026-09-24T02:52:31.7472239Z [===                                    ] 10% Computing updates...              
2026-09-24T02:52:31.7475535Z [=======================================] 100% Computing updates...             
2026-09-24T02:52:31.7475853Z 
2026-09-24T02:52:31.7692869Z ##[group]Run java -version
2026-09-24T02:52:31.7693298Z java -version
2026-09-24T02:52:31.7693636Z echo "ANDROID_HOME=$ANDROID_HOME"
2026-09-24T02:52:31.7694310Z echo "ANDROID_SDK_ROOT=$ANDROID_SDK_ROOT"
2026-09-24T02:52:31.7694738Z ls "$ANDROID_HOME/platforms"
2026-09-24T02:52:31.7695109Z ls "$ANDROID_HOME/build-tools"
2026-09-24T02:52:31.7695643Z /usr/local/lib/android/sdk/cmdline-tools/latest/bin/sdkmanager --version
2026-09-24T02:52:31.7765124Z shell: /usr/bin/bash -e {0}
2026-09-24T02:52:31.7765466Z env:
2026-09-24T02:52:31.7765726Z   KEYSTORE_BASE64: 
2026-09-24T02:52:31.7766094Z   JAVA_HOME: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-24T02:52:31.7766511Z   JAVA_HOME_17_X64: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-24T02:52:31.7767083Z ##[endgroup]
2026-09-24T02:52:31.8212091Z openjdk version "17.0.20.1" 2026-08-18
2026-09-24T02:52:31.8214749Z OpenJDK Runtime Environment Temurin-17.0.20.1+1 (build 17.0.20.1+1)
2026-09-24T02:52:31.8215881Z OpenJDK 64-Bit Server VM Temurin-17.0.20.1+1 (build 17.0.20.1+1, mixed mode, sharing)
2026-09-24T02:52:31.8304546Z ANDROID_HOME=/usr/local/lib/android/sdk
2026-09-24T02:52:31.8324276Z ANDROID_SDK_ROOT=/usr/local/lib/android/sdk
2026-09-24T02:52:31.8334163Z android-34
2026-09-24T02:52:31.8344312Z android-34-ext10
2026-09-24T02:52:31.8354156Z android-34-ext11
2026-09-24T02:52:31.8354439Z android-34-ext12
2026-09-24T02:52:31.8354718Z android-34-ext8
2026-09-24T02:52:31.8354987Z android-35
2026-09-24T02:52:31.8355235Z android-35-ext14
2026-09-24T02:52:31.8355496Z android-35-ext15
2026-09-24T02:52:31.8355803Z android-36
2026-09-24T02:52:31.8384362Z android-36-ext18
2026-09-24T02:52:31.8402231Z android-36-ext19
2026-09-24T02:52:31.8414791Z android-36.1
2026-09-24T02:52:31.8424266Z android-37.0
2026-09-24T02:52:31.8440386Z android-37.1
2026-09-24T02:52:31.8444996Z android-37.2
2026-09-24T02:52:31.8445410Z android-37.2-beta1
2026-09-24T02:52:31.8445995Z android-37.2-beta2
2026-09-24T02:52:31.8446416Z android-37.2-beta3
2026-09-24T02:52:31.8446832Z 34.0.0
2026-09-24T02:52:31.8447156Z 35.0.0
2026-09-24T02:52:31.8447474Z 35.0.1
2026-09-24T02:52:31.8455480Z 36.0.0
2026-09-24T02:52:31.8456302Z 36.1.0
2026-09-24T02:52:31.8456754Z 37.0.0
2026-09-24T02:52:32.6499836Z 12.0
2026-09-24T02:52:32.6504295Z 
2026-09-24T02:52:32.6850818Z ##[group]Run gradle/actions/setup-gradle@v4
2026-09-24T02:52:32.6851082Z with:
2026-09-24T02:52:32.6851263Z   gradle-version: 9.4.1
2026-09-24T02:52:32.6851464Z   cache-read-only: false
2026-09-24T02:52:32.6851674Z   cache-disabled: false
2026-09-24T02:52:32.6851868Z   cache-write-only: false
2026-09-24T02:52:32.6852081Z   cache-overwrite-existing: false
2026-09-24T02:52:32.6852306Z   cache-cleanup: on-success
2026-09-24T02:52:32.6852569Z   gradle-home-cache-includes: caches
notifications

2026-09-24T02:52:32.6852834Z   add-job-summary: always
2026-09-24T02:52:32.6853046Z   add-job-summary-as-pr-comment: never
2026-09-24T02:52:32.6853293Z   dependency-graph: disabled
2026-09-24T02:52:32.6853554Z   dependency-graph-report-dir: dependency-graph-reports
2026-09-24T02:52:32.6853988Z   dependency-graph-continue-on-failure: true
2026-09-24T02:52:32.6854237Z   build-scan-publish: false
2026-09-24T02:52:32.6854442Z   validate-wrappers: true
2026-09-24T02:52:32.6854695Z   allow-snapshot-wrappers: false
2026-09-24T02:52:32.6854930Z   gradle-home-cache-strict-match: false
2026-09-24T02:52:32.6855169Z   workflow-job-context: null
2026-09-24T02:52:32.6857143Z   github-token: ***
2026-09-24T02:52:32.6857322Z env:
2026-09-24T02:52:32.6857487Z   KEYSTORE_BASE64: 
2026-09-24T02:52:32.6857756Z   JAVA_HOME: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-24T02:52:32.6858144Z   JAVA_HOME_17_X64: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-24T02:52:32.6858458Z ##[endgroup]
2026-09-24T02:52:33.0728546Z Merged default JDK locations into /home/runner/.m2/toolchains.xml
2026-09-24T02:52:33.0754501Z Preparing cache for cleanup.
2026-09-24T02:52:33.0766355Z ##[group]Restore Gradle state from cache
2026-09-24T02:52:33.2961557Z Cache hit for restore-key: gradle-home-v1|Linux-X64|build[620c74083efa5b88ef904c2356f72d31]-bd63ba48962b6cf8ec27d09526ab1acf9229c775
2026-09-24T02:52:34.5272196Z Received 25165824 of 78032573 (32.3%), 24.0 MBs/sec
2026-09-24T02:52:34.9934388Z Received 78032573 of 78032573 (100.0%), 50.8 MBs/sec
2026-09-24T02:52:34.9935789Z Cache Size: ~74 MB (78032573 B)
2026-09-24T02:52:34.9961262Z [command]/usr/bin/tar -xf /home/runner/work/_temp/2f818a8e-3015-419a-83bd-59fd747bd128/cache.tzst -P -C /home/runner/work/MultiSessionBrowser/MultiSessionBrowser --use-compress-program unzstd
2026-09-24T02:52:35.1722942Z Cache restored successfully
2026-09-24T02:52:35.1756927Z Restored cache entry with key gradle-home-v1|Linux-X64|build[620c74083efa5b88ef904c2356f72d31]-d1ef99e61703ec4af135158340a820e9eadd24ff to /home/runner/.gradle/caches,/home/runner/.gradle/notifications,/home/runner/.gradle/.setup-gradle in 2101ms
2026-09-24T02:52:35.3847723Z Cache hit for: gradle-instrumented-jars-v1-65e43839ec15e14466462eac23ed3666
2026-09-24T02:52:35.3852257Z Cache hit for: gradle-dependencies-v1-ff8f53da8fa887b6e94ec6514e1aa600
2026-09-24T02:52:35.3926592Z Cache hit for: gradle-groovy-dsl-v1-ef963d9b486a939759d0fbb48dc06307
2026-09-24T02:52:35.3989406Z Cache hit for: gradle-kotlin-dsl-v1-d48120fc299f65325949b1f9313862b6
2026-09-24T02:52:35.4015362Z Cache hit for: gradle-transforms-v1-5ff61efe39ba55885242d032c4af6357
2026-09-24T02:52:35.4035452Z Cache hit for: gradle-generated-gradle-jars-v1-8e79c31f3eb4e570a88142dd102a0a2f
2026-09-24T02:52:35.7712939Z Received 88955 of 88955 (100.0%), 0.6 MBs/sec
2026-09-24T02:52:35.7715245Z Cache Size: ~0 MB (88955 B)
2026-09-24T02:52:35.7880304Z [command]/usr/bin/tar -xf /home/runner/work/_temp/963f1917-288f-4f32-8746-25556715b3d7/cache.tzst -P -C /home/runner/work/MultiSessionBrowser/MultiSessionBrowser --use-compress-program unzstd
2026-09-24T02:52:35.8285443Z Cache restored successfully
2026-09-24T02:52:35.8305273Z Restored cache entry with key gradle-kotlin-dsl-v1-d48120fc299f65325949b1f9313862b6 to /home/runner/.gradle/caches/*/kotlin-dsl/accessors/*/,/home/runner/.gradle/caches/*/kotlin-dsl/scripts/*/ in 653ms
2026-09-24T02:52:35.9806146Z Received 101743 of 101743 (100.0%), 0.3 MBs/sec
2026-09-24T02:52:35.9807329Z Cache Size: ~0 MB (101743 B)
2026-09-24T02:52:35.9835415Z [command]/usr/bin/tar -xf /home/runner/work/_temp/bec85986-6b79-4801-af37-765086fd9b1f/cache.tzst -P -C /home/runner/work/MultiSessionBrowser/MultiSessionBrowser --use-compress-program unzstd
2026-09-24T02:52:35.9999523Z Cache restored successfully
2026-09-24T02:52:36.0001491Z Restored cache entry with key gradle-groovy-dsl-v1-ef963d9b486a939759d0fbb48dc06307 to /home/runner/.gradle/caches/*/groovy-dsl/*/ in 823ms
2026-09-24T02:52:36.0018672Z Received 64703 of 64703 (100.0%), 0.2 MBs/sec
2026-09-24T02:52:36.0019284Z Cache Size: ~0 MB (64703 B)
2026-09-24T02:52:36.0043535Z [command]/usr/bin/tar -xf /home/runner/work/_temp/ae773e38-60bb-45f8-90e8-34a20da1dc65/cache.tzst -P -C /home/runner/work/MultiSessionBrowser/MultiSessionBrowser --use-compress-program unzstd
2026-09-24T02:52:36.0151258Z Cache restored successfully
2026-09-24T02:52:36.0153358Z Restored cache entry with key gradle-instrumented-jars-v1-65e43839ec15e14466462eac23ed3666 to /home/runner/.gradle/caches/jars-*/*/ in 839ms
2026-09-24T02:52:36.5915354Z Received 33554432 of 652238655 (5.1%), 31.9 MBs/sec
2026-09-24T02:52:36.6026537Z Received 29360128 of 360259019 (8.1%), 28.0 MBs/sec
2026-09-24T02:52:36.6344783Z Received 16777216 of 41895873 (40.0%), 16.0 MBs/sec
2026-09-24T02:52:36.8106809Z Received 41895873 of 41895873 (100.0%), 33.9 MBs/sec
2026-09-24T02:52:36.8107790Z Cache Size: ~40 MB (41895873 B)
2026-09-24T02:52:36.8205611Z [command]/usr/bin/tar -xf /home/runner/work/_temp/3b8eaa20-f319-4af5-a019-e59212558556/cache.tzst -P -C /home/runner/work/MultiSessionBrowser/MultiSessionBrowser --use-compress-program unzstd
2026-09-24T02:52:37.3348516Z Cache restored successfully
2026-09-24T02:52:37.3382617Z Restored cache entry with key gradle-generated-gradle-jars-v1-8e79c31f3eb4e570a88142dd102a0a2f to /home/runner/.gradle/caches/9.4.1/generated-gradle-jars/gradle-api-9.4.1.jar in 2162ms
2026-09-24T02:52:37.5913378Z Received 130023424 of 652238655 (19.9%), 61.9 MBs/sec
2026-09-24T02:52:37.6305008Z Received 134217728 of 360259019 (37.3%), 63.2 MBs/sec
2026-09-24T02:52:38.6753103Z Received 268435456 of 652238655 (41.2%), 83.1 MBs/sec
2026-09-24T02:52:38.6756931Z Received 268435456 of 360259019 (74.5%), 83.4 MBs/sec
2026-09-24T02:52:39.1504909Z Received 360259019 of 360259019 (100.0%), 96.8 MBs/sec
2026-09-24T02:52:39.1506545Z Cache Size: ~344 MB (360259019 B)
2026-09-24T02:52:39.1908441Z [command]/usr/bin/tar -xf /home/runner/work/_temp/35d6648b-2906-4472-9958-af52622a73e4/cache.tzst -P -C /home/runner/work/MultiSessionBrowser/MultiSessionBrowser --use-compress-program unzstd
2026-09-24T02:52:39.6723443Z Received 402653184 of 652238655 (61.7%), 94.0 MBs/sec
2026-09-24T02:52:42.0767657Z Received 536870912 of 652238655 (82.3%), 78.9 MBs/sec
2026-09-24T02:52:43.7758348Z Received 652238655 of 652238655 (100.0%), 76.0 MBs/sec
2026-09-24T02:52:43.7760049Z Cache Size: ~622 MB (652238655 B)
2026-09-24T02:52:43.7783470Z [command]/usr/bin/tar -xf /home/runner/work/_temp/70b22db8-bd8e-4499-8a16-e5bb84665e8f/cache.tzst -P -C /home/runner/work/MultiSessionBrowser/MultiSessionBrowser --use-compress-program unzstd
2026-09-24T02:52:56.3628440Z Cache restored successfully
2026-09-24T02:52:56.6300642Z Restored cache entry with key gradle-transforms-v1-5ff61efe39ba55885242d032c4af6357 to /home/runner/.gradle/caches/transforms-4/*/,/home/runner/.gradle/caches/*/transforms/*/ in 21452ms
2026-09-24T02:52:56.9718095Z Cache restored successfully
2026-09-24T02:52:57.2585181Z Restored cache entry with key gradle-dependencies-v1-ff8f53da8fa887b6e94ec6514e1aa600 to /home/runner/.gradle/caches/modules-*/files-*/*/*/*/* in 22081ms
2026-09-24T02:52:57.6829882Z ##[endgroup]
2026-09-24T02:52:57.6878255Z ##[group]All Gradle Wrapper jars are valid
2026-09-24T02:52:57.6880142Z 
2026-09-24T02:52:57.6880834Z ##[endgroup]
2026-09-24T02:52:58.0291680Z ##[group]Provision Gradle 9.4.1
2026-09-24T02:53:03.9235144Z Cache hit for: gradle-9.4.1
2026-09-24T02:53:05.1258750Z Received 25165824 of 137779930 (18.3%), 24.0 MBs/sec
2026-09-24T02:53:06.0059247Z Received 137779930 of 137779930 (100.0%), 69.9 MBs/sec
2026-09-24T02:53:06.0060575Z Cache Size: ~131 MB (137779930 B)
2026-09-24T02:53:06.0087123Z [command]/usr/bin/tar -xf /home/runner/work/_temp/f1ef3d0d-bb60-45c6-b2fa-42174a960ec8/cache.tzst -P -C /home/runner/work/MultiSessionBrowser/MultiSessionBrowser --use-compress-program unzstd
2026-09-24T02:53:06.1748684Z Cache restored successfully
2026-09-24T02:53:06.1810423Z Restored Gradle distribution gradle-9.4.1 from cache to /home/runner/work/_temp/.gradle-actions/gradle-installations/downloads/gradle-9.4.1-bin.zip
2026-09-24T02:53:06.1826642Z [command]/usr/bin/unzip -o -q /home/runner/work/_temp/.gradle-actions/gradle-installations/downloads/gradle-9.4.1-bin.zip
2026-09-24T02:53:07.0401733Z Extracted Gradle 9.4.1 to /home/runner/work/_temp/.gradle-actions/gradle-installations/installs/gradle-9.4.1
2026-09-24T02:53:07.0403225Z Provisioned Gradle executable /home/runner/work/_temp/.gradle-actions/gradle-installations/installs/gradle-9.4.1/bin/gradle
2026-09-24T02:53:07.0411293Z ##[endgroup]
2026-09-24T02:53:07.0576624Z ##[group]Run gradle assembleDebug --no-daemon --stacktrace
2026-09-24T02:53:07.0577075Z gradle assembleDebug --no-daemon --stacktrace
2026-09-24T02:53:07.0632798Z shell: /usr/bin/bash -e {0}
2026-09-24T02:53:07.0633074Z env:
2026-09-24T02:53:07.0633289Z   KEYSTORE_BASE64: 
2026-09-24T02:53:07.0633628Z   JAVA_HOME: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-24T02:53:07.0634209Z   JAVA_HOME_17_X64: /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.20-1/x64
2026-09-24T02:53:07.0634612Z   GRADLE_ACTION_ID: gradle/actions/setup-gradle
2026-09-24T02:53:07.0634925Z   GRADLE_USER_HOME: /home/runner/.gradle
2026-09-24T02:53:07.0635216Z   GRADLE_BUILD_ACTION_SETUP_COMPLETED: true
2026-09-24T02:53:07.0635510Z   GRADLE_BUILD_ACTION_CACHE_RESTORED: true
2026-09-24T02:53:07.0635917Z   DEVELOCITY_INJECTION_INIT_SCRIPT_NAME: gradle-actions.inject-develocity.init.gradle
2026-09-24T02:53:07.0636355Z   DEVELOCITY_INJECTION_CUSTOM_VALUE: gradle-actions
2026-09-24T02:53:07.0636715Z   GITHUB_DEPENDENCY_GRAPH_ENABLED: false
2026-09-24T02:53:07.0636988Z ##[endgroup]
2026-09-24T02:53:07.8551497Z To honour the JVM settings for this build a single-use Daemon process will be forked. For more on this, please refer to https://docs.gradle.org/9.4.1/userguide/gradle_daemon.html#sec:disabling_the_daemon in the Gradle documentation.
2026-09-24T02:53:09.2534792Z Daemon will be stopped at the end of the build 
2026-09-24T02:53:26.3565737Z WARNING: We recommend using a newer Android Gradle plugin to use compile SDK version 37.1
2026-09-24T02:53:26.3607966Z 
2026-09-24T02:53:26.3644969Z This Android Gradle plugin (9.2.1) was tested up to compile SDK version 37.0.
2026-09-24T02:53:26.3664239Z 
2026-09-24T02:53:26.3699589Z You are strongly encouraged to update your project to use a newer
2026-09-24T02:53:26.3724777Z Android Gradle plugin that has been tested with compile SDK version 37.1.
2026-09-24T02:53:26.3734344Z 
2026-09-24T02:53:26.3755515Z If you are already using the latest version of the Android Gradle plugin,
2026-09-24T02:53:26.3784656Z you may need to wait until a newer version with support for compile SDK version 37.1 is available.
2026-09-24T02:53:26.3805727Z 
2026-09-24T02:53:26.3824845Z For more information refer to the compatibility table:
2026-09-24T02:53:26.3844220Z https://d.android.com/r/tools/api-level-support
2026-09-24T02:53:26.3871538Z 
2026-09-24T02:53:26.3890569Z To suppress this warning, add/update
2026-09-24T02:53:26.3925929Z     android.suppressUnsupportedCompileSdk=37.1
2026-09-24T02:53:26.3926533Z to this project's gradle.properties.
2026-09-24T02:53:26.6835354Z > Task :app:preBuild UP-TO-DATE
2026-09-24T02:53:26.6836196Z > Task :app:preDebugBuild UP-TO-DATE
2026-09-24T02:53:26.7055121Z > Task :app:mergeDebugNativeDebugMetadata NO-SOURCE
2026-09-24T02:53:26.7766275Z > Task :app:generateDebugBuildConfig
2026-09-24T02:53:26.8533842Z > Task :app:generateDebugResources
2026-09-24T02:53:27.5735811Z > Task :app:packageDebugResources
2026-09-24T02:53:28.1542078Z > Task :app:processDebugNavigationResources
2026-09-24T02:53:29.5545116Z > Task :app:javaPreCompileDebug FROM-CACHE
2026-09-24T02:53:29.5564849Z > Task :app:generateDebugAssets UP-TO-DATE
2026-09-24T02:53:29.7536379Z > Task :app:parseDebugLocalResources
2026-09-24T02:53:29.8564936Z > Task :app:mergeDebugAssets
2026-09-24T02:53:29.9532776Z > Task :app:compressDebugAssets FROM-CACHE
2026-09-24T02:53:30.0534918Z > Task :app:generateDebugRFile
2026-09-24T02:53:30.5555276Z > Task :app:generateDebugGlobalSynthetics FROM-CACHE
2026-09-24T02:53:30.8555746Z > Task :app:checkDebugDuplicateClasses
2026-09-24T02:53:30.8560752Z > Task :app:desugarDebugFileDependencies FROM-CACHE
2026-09-24T02:53:31.4555165Z > Task :app:mergeExtDexDebug FROM-CACHE
2026-09-24T02:53:31.4577334Z > Task :app:mergeLibDexDebug FROM-CACHE
2026-09-24T02:53:31.6534931Z > Task :app:checkDebugAarMetadata
2026-09-24T02:53:31.6585067Z > Task :app:mapDebugSourceSetPaths
2026-09-24T02:53:31.6635154Z > Task :app:compileDebugNavigationResources FROM-CACHE
2026-09-24T02:53:37.3544837Z > Task :app:mergeDebugResources
2026-09-24T02:53:37.3594549Z > Task :app:createDebugCompatibleScreenManifests
2026-09-24T02:53:37.3635277Z > Task :app:extractDeepLinksDebug FROM-CACHE
2026-09-24T02:53:38.0565006Z > Task :app:processDebugMainManifest
2026-09-24T02:53:38.3532765Z > Task :app:processDebugManifest
2026-09-24T02:53:38.3554361Z > Task :app:processDebugManifestForPackage
2026-09-24T02:53:40.2555141Z > Task :app:processDebugResources FAILED
2026-09-24T02:53:47.3535002Z > Task :app:kspDebugKotlin
2026-09-24T02:53:47.3570472Z gradle/actions: Writing build results to /home/runner/work/_temp/.gradle-actions/build-results/__run_4-1790218391895.json
2026-09-24T02:53:47.4556270Z 
2026-09-24T02:53:47.4557416Z FAILURE: Build failed with an exception.
2026-09-24T02:53:47.4601287Z 
2026-09-24T02:53:47.4644471Z * What went wrong:
2026-09-24T02:53:47.4644963Z Execution failed for task ':app:processDebugResources'.
2026-09-24T02:53:47.4645879Z > A failure occurred while executing com.android.build.gradle.internal.res.LinkApplicationAndroidResourcesTask$TaskAction
2026-09-24T02:53:47.4646697Z    > Android resource linking failed
2026-09-24T02:53:47.4648508Z      app.multisession.browser.app-main-51:/layout/activity_browser.xml:204: error: 'center_end' is incompatible with attribute layout_gravity (attr) flags [bottom=80, center=17, center_horizontal=1, center_vertical=16, clip_horizontal=8, clip_vertical=128, end=8388613, fill=119, fill_horizontal=7, fill_vertical=112, left=3, right=5, start=8388611, top=48].
2026-09-24T02:53:47.4650845Z      error: failed linking file resources.
2026-09-24T02:53:47.4651143Z 
2026-09-24T02:53:47.4651149Z 
2026-09-24T02:53:47.4651284Z * Try:
2026-09-24T02:53:47.4651698Z > Run with --info or --debug option to get more log output.
2026-09-24T02:53:47.4652434Z > Run with --scan to get full insights from a Build Scan (powered by Develocity).
2026-09-24T02:53:47.4674545Z > Get more help at https://help.gradle.org.
2026-09-24T02:53:47.4689351Z 
2026-09-24T02:53:47.4724249Z * Exception is:
2026-09-24T02:53:47.4724700Z 25 actionable tasks: 17 executed, 8 from cache
2026-09-24T02:53:47.4744784Z org.gradle.api.tasks.TaskExecutionException: Execution failed for task ':app:processDebugResources'.
2026-09-24T02:53:47.4765012Z 	at org.gradle.api.internal.tasks.execution.ExecuteActionsTaskExecuter.lambda$executeIfValid$1(ExecuteActionsTaskExecuter.java:135)
2026-09-24T02:53:47.4784365Z 	at org.gradle.internal.Try$Failure.ifSuccessfulOrElse(Try.java:288)
2026-09-24T02:53:47.4785672Z 	at org.gradle.api.internal.tasks.execution.ExecuteActionsTaskExecuter.executeIfValid(ExecuteActionsTaskExecuter.java:133)
2026-09-24T02:53:47.4803676Z 	at org.gradle.api.internal.tasks.execution.ExecuteActionsTaskExecuter.execute(ExecuteActionsTaskExecuter.java:121)
2026-09-24T02:53:47.4824919Z 	at org.gradle.api.internal.tasks.execution.ProblemsTaskPathTrackingTaskExecuter.execute(ProblemsTaskPathTrackingTaskExecuter.java:41)
2026-09-24T02:53:47.4844769Z 	at org.gradle.api.internal.tasks.execution.ResolveTaskExecutionModeExecuter.execute(ResolveTaskExecutionModeExecuter.java:51)
2026-09-24T02:53:47.4864728Z 	at org.gradle.api.internal.tasks.execution.FinalizePropertiesTaskExecuter.execute(FinalizePropertiesTaskExecuter.java:46)
2026-09-24T02:53:47.4866568Z 	at org.gradle.api.internal.tasks.execution.SkipTaskWithNoActionsExecuter.execute(SkipTaskWithNoActionsExecuter.java:57)
2026-09-24T02:53:47.4884621Z 	at org.gradle.api.internal.tasks.execution.SkipOnlyIfTaskExecuter.execute(SkipOnlyIfTaskExecuter.java:74)
2026-09-24T02:53:47.4904628Z 	at org.gradle.api.internal.tasks.execution.CatchExceptionTaskExecuter.execute(CatchExceptionTaskExecuter.java:36)
2026-09-24T02:53:47.4924541Z 	at org.gradle.api.internal.tasks.execution.EventFiringTaskExecuter$1.executeTask(EventFiringTaskExecuter.java:77)
2026-09-24T02:53:47.4925831Z 	at org.gradle.api.internal.tasks.execution.EventFiringTaskExecuter$1.call(EventFiringTaskExecuter.java:55)
2026-09-24T02:53:47.4927533Z 	at org.gradle.api.internal.tasks.execution.EventFiringTaskExecuter$1.call(EventFiringTaskExecuter.java:52)
2026-09-24T02:53:47.4928958Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:210)
2026-09-24T02:53:47.4930520Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:205)
2026-09-24T02:53:47.4954500Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:67)
2026-09-24T02:53:47.4964570Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:60)
2026-09-24T02:53:47.4984557Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:167)
2026-09-24T02:53:47.4985998Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:60)
2026-09-24T02:53:47.5004778Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.call(DefaultBuildOperationRunner.java:54)
2026-09-24T02:53:47.5024699Z 	at org.gradle.api.internal.tasks.execution.EventFiringTaskExecuter.execute(EventFiringTaskExecuter.java:52)
2026-09-24T02:53:47.5044615Z 	at org.gradle.execution.plan.DefaultNodeExecutor.executeLocalTaskNode(DefaultNodeExecutor.java:55)
2026-09-24T02:53:47.5045798Z 	at org.gradle.execution.plan.DefaultNodeExecutor.execute(DefaultNodeExecutor.java:34)
2026-09-24T02:53:47.5064662Z 	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$InvokeNodeExecutorsAction.execute(DefaultTaskExecutionGraph.java:355)
2026-09-24T02:53:47.5084847Z 	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$InvokeNodeExecutorsAction.execute(DefaultTaskExecutionGraph.java:343)
2026-09-24T02:53:47.5104887Z 	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$BuildOperationAwareExecutionAction.lambda$execute$0(DefaultTaskExecutionGraph.java:339)
2026-09-24T02:53:47.5124591Z 	at org.gradle.internal.operations.CurrentBuildOperationRef.with(CurrentBuildOperationRef.java:84)
2026-09-24T02:53:47.5126182Z 	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$BuildOperationAwareExecutionAction.execute(DefaultTaskExecutionGraph.java:339)
2026-09-24T02:53:47.5144883Z 	at org.gradle.execution.taskgraph.DefaultTaskExecutionGraph$BuildOperationAwareExecutionAction.execute(DefaultTaskExecutionGraph.java:328)
2026-09-24T02:53:47.5164579Z 	at org.gradle.execution.plan.DefaultPlanExecutor$ExecutorWorker.execute(DefaultPlanExecutor.java:459)
2026-09-24T02:53:47.5185223Z 	at org.gradle.execution.plan.DefaultPlanExecutor$ExecutorWorker.run(DefaultPlanExecutor.java:376)
2026-09-24T02:53:47.5204503Z 	at org.gradle.internal.concurrent.ExecutorPolicy$CatchAndRecordFailures.onExecute(ExecutorPolicy.java:64)
2026-09-24T02:53:47.5205744Z 	at org.gradle.internal.concurrent.AbstractManagedExecutor$1.run(AbstractManagedExecutor.java:47)
2026-09-24T02:53:47.5225146Z Caused by: org.gradle.workers.internal.DefaultWorkerExecutor$WorkExecutionException: A failure occurred while executing com.android.build.gradle.internal.res.LinkApplicationAndroidResourcesTask$TaskAction
2026-09-24T02:53:47.5244542Z 	at org.gradle.workers.internal.DefaultWorkerExecutor$WorkItemExecution.waitForCompletion(DefaultWorkerExecutor.java:289)
2026-09-24T02:53:47.5264620Z 	at org.gradle.internal.work.DefaultAsyncWorkTracker.lambda$waitForItemsAndGatherFailures$2(DefaultAsyncWorkTracker.java:130)
2026-09-24T02:53:47.5265769Z 	at org.gradle.internal.Factories$1.create(Factories.java:30)
2026-09-24T02:53:47.5284731Z 	at org.gradle.internal.work.DefaultWorkerLeaseService.lambda$withoutLocks$2(DefaultWorkerLeaseService.java:350)
2026-09-24T02:53:47.5304646Z 	at org.gradle.internal.work.ResourceLockStatistics$1.measure(ResourceLockStatistics.java:43)
2026-09-24T02:53:47.5324635Z 	at org.gradle.internal.work.DefaultWorkerLeaseService.withoutLocks(DefaultWorkerLeaseService.java:348)
2026-09-24T02:53:47.5345179Z 	at org.gradle.internal.work.DefaultWorkerLeaseService.withoutLocks(DefaultWorkerLeaseService.java:332)
2026-09-24T02:53:47.5346541Z 	at org.gradle.internal.work.DefaultWorkerLeaseService.withoutLock(DefaultWorkerLeaseService.java:337)
2026-09-24T02:53:47.5364734Z 	at org.gradle.internal.work.DefaultAsyncWorkTracker.waitForItemsAndGatherFailures(DefaultAsyncWorkTracker.java:126)
2026-09-24T02:53:47.5384684Z 	at org.gradle.internal.work.DefaultAsyncWorkTracker.waitForItemsAndGatherFailures(DefaultAsyncWorkTracker.java:92)
2026-09-24T02:53:47.5404518Z 	at org.gradle.internal.work.DefaultAsyncWorkTracker.waitForAll(DefaultAsyncWorkTracker.java:78)
2026-09-24T02:53:47.5405787Z 	at org.gradle.internal.work.DefaultAsyncWorkTracker.waitForCompletion(DefaultAsyncWorkTracker.java:66)
2026-09-24T02:53:47.5424428Z 	at org.gradle.api.internal.tasks.execution.TaskExecution$3.run(TaskExecution.java:267)
2026-09-24T02:53:47.5444500Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$1.execute(DefaultBuildOperationRunner.java:30)
2026-09-24T02:53:47.5464671Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$1.execute(DefaultBuildOperationRunner.java:27)
2026-09-24T02:53:47.5484600Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:67)
2026-09-24T02:53:47.5502201Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:60)
2026-09-24T02:53:47.5524692Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:167)
2026-09-24T02:53:47.5547700Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:60)
2026-09-24T02:53:47.5562141Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.run(DefaultBuildOperationRunner.java:48)
2026-09-24T02:53:47.5574136Z 	at org.gradle.api.internal.tasks.execution.TaskExecution.executeAction(TaskExecution.java:244)
2026-09-24T02:53:47.5594718Z 	at org.gradle.api.internal.tasks.execution.TaskExecution.executeActions(TaskExecution.java:227)
2026-09-24T02:53:47.5596138Z 	at org.gradle.api.internal.tasks.execution.TaskExecution.executeWithPreviousOutputFiles(TaskExecution.java:210)
2026-09-24T02:53:47.5597240Z 	at org.gradle.api.internal.tasks.execution.TaskExecution.execute(TaskExecution.java:176)
2026-09-24T02:53:47.5598173Z 	at org.gradle.internal.execution.steps.ExecuteStep.executeInternal(ExecuteStep.java:167)
2026-09-24T02:53:47.5599055Z 	at org.gradle.internal.execution.steps.ExecuteStep.access$000(ExecuteStep.java:47)
2026-09-24T02:53:47.5599940Z 	at org.gradle.internal.execution.steps.ExecuteStep$1.call(ExecuteStep.java:137)
2026-09-24T02:53:47.5600785Z 	at org.gradle.internal.execution.steps.ExecuteStep$1.call(ExecuteStep.java:134)
2026-09-24T02:53:47.5601830Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:210)
2026-09-24T02:53:47.5603157Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:205)
2026-09-24T02:53:47.5604652Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:67)
2026-09-24T02:53:47.5605883Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:60)
2026-09-24T02:53:47.5610694Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:167)
2026-09-24T02:53:47.5612081Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:60)
2026-09-24T02:53:47.5613377Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.call(DefaultBuildOperationRunner.java:54)
2026-09-24T02:53:47.5614670Z 	at org.gradle.internal.execution.steps.ExecuteStep.execute(ExecuteStep.java:134)
2026-09-24T02:53:47.5615664Z 	at org.gradle.internal.execution.steps.ExecuteStep$Mutable.execute(ExecuteStep.java:80)
2026-09-24T02:53:47.5617336Z 	at org.gradle.internal.execution.steps.CancelExecutionStep.execute(CancelExecutionStep.java:42)
2026-09-24T02:53:47.5618388Z 	at org.gradle.internal.execution.steps.TimeoutStep.executeWithoutTimeout(TimeoutStep.java:75)
2026-09-24T02:53:47.5619237Z 	at org.gradle.internal.execution.steps.TimeoutStep.execute(TimeoutStep.java:55)
2026-09-24T02:53:47.5620182Z 	at org.gradle.internal.execution.steps.PreCreateOutputParentsStep.execute(PreCreateOutputParentsStep.java:51)
2026-09-24T02:53:47.5621274Z 	at org.gradle.internal.execution.steps.PreCreateOutputParentsStep.execute(PreCreateOutputParentsStep.java:29)
2026-09-24T02:53:47.5622391Z 	at org.gradle.internal.execution.steps.RemovePreviousOutputsStep.executeMutable(RemovePreviousOutputsStep.java:67)
2026-09-24T02:53:47.5623515Z 	at org.gradle.internal.execution.steps.RemovePreviousOutputsStep.executeMutable(RemovePreviousOutputsStep.java:39)
2026-09-24T02:53:47.5624596Z 	at org.gradle.internal.execution.steps.MutableStep.execute(MutableStep.java:26)
2026-09-24T02:53:47.5625560Z 	at org.gradle.internal.execution.steps.BroadcastChangingOutputsStep.execute(BroadcastChangingOutputsStep.java:42)
2026-09-24T02:53:47.5626653Z 	at org.gradle.internal.execution.steps.BroadcastChangingOutputsStep.execute(BroadcastChangingOutputsStep.java:24)
2026-09-24T02:53:47.5627797Z 	at org.gradle.internal.execution.steps.CaptureOutputsAfterExecutionStep.execute(CaptureOutputsAfterExecutionStep.java:69)
2026-09-24T02:53:47.5628984Z 	at org.gradle.internal.execution.steps.CaptureOutputsAfterExecutionStep.execute(CaptureOutputsAfterExecutionStep.java:46)
2026-09-24T02:53:47.5630323Z 	at org.gradle.internal.execution.steps.ResolveInputChangesStep.executeMutable(ResolveInputChangesStep.java:39)
2026-09-24T02:53:47.5631393Z 	at org.gradle.internal.execution.steps.ResolveInputChangesStep.executeMutable(ResolveInputChangesStep.java:28)
2026-09-24T02:53:47.5632325Z 	at org.gradle.internal.execution.steps.MutableStep.execute(MutableStep.java:26)
2026-09-24T02:53:47.5633184Z 	at org.gradle.internal.execution.steps.BuildCacheStep.executeWithoutCache(BuildCacheStep.java:189)
2026-09-24T02:53:47.5634386Z 	at org.gradle.internal.execution.steps.BuildCacheStep.executeAndStoreInCache(BuildCacheStep.java:145)
2026-09-24T02:53:47.5635618Z 	at org.gradle.internal.execution.steps.BuildCacheStep.lambda$executeWithCache$4(BuildCacheStep.java:104)
2026-09-24T02:53:47.5636716Z 	at org.gradle.internal.execution.steps.BuildCacheStep.lambda$executeWithCache$5(BuildCacheStep.java:104)
2026-09-24T02:53:47.5637582Z 	at org.gradle.internal.Try$Success.map(Try.java:170)
2026-09-24T02:53:47.5638415Z 	at org.gradle.internal.execution.steps.BuildCacheStep.executeWithCache(BuildCacheStep.java:88)
2026-09-24T02:53:47.5639456Z 	at org.gradle.internal.execution.steps.BuildCacheStep.lambda$execute$0(BuildCacheStep.java:75)
2026-09-24T02:53:47.5640310Z 	at org.gradle.internal.Either$Left.fold(Either.java:116)
2026-09-24T02:53:47.5641058Z 	at org.gradle.internal.execution.caching.CachingState.fold(CachingState.java:62)
2026-09-24T02:53:47.5641979Z 	at org.gradle.internal.execution.steps.BuildCacheStep.execute(BuildCacheStep.java:74)
2026-09-24T02:53:47.5643036Z 	at org.gradle.internal.execution.steps.BuildCacheStep.execute(BuildCacheStep.java:49)
2026-09-24T02:53:47.5644205Z 	at org.gradle.internal.execution.steps.StoreExecutionStateStep.executeMutable(StoreExecutionStateStep.java:46)
2026-09-24T02:53:47.5645286Z 	at org.gradle.internal.execution.steps.StoreExecutionStateStep.executeMutable(StoreExecutionStateStep.java:35)
2026-09-24T02:53:47.5646234Z 	at org.gradle.internal.execution.steps.MutableStep.execute(MutableStep.java:26)
2026-09-24T02:53:47.5647086Z 	at org.gradle.internal.execution.steps.SkipUpToDateStep.executeBecause(SkipUpToDateStep.java:75)
2026-09-24T02:53:47.5648034Z 	at org.gradle.internal.execution.steps.SkipUpToDateStep.lambda$execute$2(SkipUpToDateStep.java:53)
2026-09-24T02:53:47.5648936Z 	at org.gradle.internal.execution.steps.SkipUpToDateStep.execute(SkipUpToDateStep.java:53)
2026-09-24T02:53:47.5649978Z 	at org.gradle.internal.execution.steps.SkipUpToDateStep.execute(SkipUpToDateStep.java:35)
2026-09-24T02:53:47.5651405Z 	at org.gradle.internal.execution.steps.legacy.MarkSnapshottingInputsFinishedStep.execute(MarkSnapshottingInputsFinishedStep.java:37)
2026-09-24T02:53:47.5653022Z 	at org.gradle.internal.execution.steps.legacy.MarkSnapshottingInputsFinishedStep.execute(MarkSnapshottingInputsFinishedStep.java:27)
2026-09-24T02:53:47.5654702Z 	at org.gradle.internal.execution.steps.ResolveMutableCachingStateStep.executeDelegate(ResolveMutableCachingStateStep.java:70)
2026-09-24T02:53:47.5656189Z 	at org.gradle.internal.execution.steps.ResolveMutableCachingStateStep.executeDelegate(ResolveMutableCachingStateStep.java:32)
2026-09-24T02:53:47.5657661Z 	at org.gradle.internal.execution.steps.AbstractResolveCachingStateStep.execute(AbstractResolveCachingStateStep.java:69)
2026-09-24T02:53:47.5659060Z 	at org.gradle.internal.execution.steps.AbstractResolveCachingStateStep.execute(AbstractResolveCachingStateStep.java:37)
2026-09-24T02:53:47.5660360Z 	at org.gradle.internal.execution.steps.ResolveChangesStep.executeMutable(ResolveChangesStep.java:63)
2026-09-24T02:53:47.5661539Z 	at org.gradle.internal.execution.steps.ResolveChangesStep.executeMutable(ResolveChangesStep.java:34)
2026-09-24T02:53:47.5662582Z 	at org.gradle.internal.execution.steps.MutableStep.execute(MutableStep.java:26)
2026-09-24T02:53:47.5663562Z 	at org.gradle.internal.execution.steps.ValidateStep$Mutable.executeDelegate(ValidateStep.java:79)
2026-09-24T02:53:47.5664918Z 	at org.gradle.internal.execution.steps.ValidateStep$Mutable.executeDelegate(ValidateStep.java:65)
2026-09-24T02:53:47.5665946Z 	at org.gradle.internal.execution.steps.ValidateStep.execute(ValidateStep.java:99)
2026-09-24T02:53:47.5666922Z 	at org.gradle.internal.execution.steps.ValidateStep$Mutable.execute(ValidateStep.java:65)
2026-09-24T02:53:47.5668276Z 	at org.gradle.internal.execution.steps.CaptureMutableStateBeforeExecutionStep.executeMutable(CaptureMutableStateBeforeExecutionStep.java:86)
2026-09-24T02:53:47.5669949Z 	at org.gradle.internal.execution.steps.CaptureMutableStateBeforeExecutionStep.execute(CaptureMutableStateBeforeExecutionStep.java:65)
2026-09-24T02:53:47.5671543Z 	at org.gradle.internal.execution.steps.CaptureMutableStateBeforeExecutionStep.execute(CaptureMutableStateBeforeExecutionStep.java:45)
2026-09-24T02:53:47.5673037Z 	at org.gradle.internal.execution.steps.SkipEmptyMutableWorkStep.executeWithNonEmptySources(SkipEmptyMutableWorkStep.java:210)
2026-09-24T02:53:47.5674399Z 	at org.gradle.internal.execution.steps.SkipEmptyMutableWorkStep.executeMutable(SkipEmptyMutableWorkStep.java:85)
2026-09-24T02:53:47.5694696Z 	at org.gradle.internal.execution.steps.SkipEmptyMutableWorkStep.executeMutable(SkipEmptyMutableWorkStep.java:53)
2026-09-24T02:53:47.5698500Z 	at org.gradle.internal.execution.steps.MutableStep.execute(MutableStep.java:26)
2026-09-24T02:53:47.5734806Z 	at org.gradle.internal.execution.steps.legacy.MarkSnapshottingInputsStartedStep.execute(MarkSnapshottingInputsStartedStep.java:38)
2026-09-24T02:53:47.5736467Z 	at org.gradle.internal.execution.steps.LoadPreviousExecutionStateStep.executeMutable(LoadPreviousExecutionStateStep.java:36)
2026-09-24T02:53:47.5754678Z 	at org.gradle.internal.execution.steps.LoadPreviousExecutionStateStep.executeMutable(LoadPreviousExecutionStateStep.java:23)
2026-09-24T02:53:47.5771463Z 	at org.gradle.internal.execution.steps.MutableStep.execute(MutableStep.java:26)
2026-09-24T02:53:47.5772534Z 	at org.gradle.internal.execution.steps.HandleStaleOutputsStep.executeMutable(HandleStaleOutputsStep.java:77)
2026-09-24T02:53:47.5773871Z 	at org.gradle.internal.execution.steps.HandleStaleOutputsStep.executeMutable(HandleStaleOutputsStep.java:43)
2026-09-24T02:53:47.5774978Z 	at org.gradle.internal.execution.steps.MutableStep.execute(MutableStep.java:26)
2026-09-24T02:53:47.5776175Z 	at org.gradle.internal.execution.steps.AssignMutableWorkspaceStep.lambda$executeMutable$0(AssignMutableWorkspaceStep.java:34)
2026-09-24T02:53:47.5777833Z 	at org.gradle.api.internal.tasks.execution.TaskExecution$4.withWorkspace(TaskExecution.java:305)
2026-09-24T02:53:47.5779103Z 	at org.gradle.internal.execution.steps.AssignMutableWorkspaceStep.executeMutable(AssignMutableWorkspaceStep.java:30)
2026-09-24T02:53:47.5780630Z 	at org.gradle.internal.execution.steps.AssignMutableWorkspaceStep.executeMutable(AssignMutableWorkspaceStep.java:21)
2026-09-24T02:53:47.5781821Z 	at org.gradle.internal.execution.steps.MutableStep.execute(MutableStep.java:26)
2026-09-24T02:53:47.5782893Z 	at org.gradle.internal.execution.steps.ChoosePipelineStep.execute(ChoosePipelineStep.java:40)
2026-09-24T02:53:47.5784171Z 	at org.gradle.internal.execution.steps.ChoosePipelineStep.execute(ChoosePipelineStep.java:23)
2026-09-24T02:53:47.5785578Z 	at org.gradle.internal.execution.steps.ExecuteWorkBuildOperationFiringStep.lambda$execute$2(ExecuteWorkBuildOperationFiringStep.java:67)
2026-09-24T02:53:47.5787228Z 	at org.gradle.internal.execution.steps.ExecuteWorkBuildOperationFiringStep.execute(ExecuteWorkBuildOperationFiringStep.java:67)
2026-09-24T02:53:47.5788830Z 	at org.gradle.internal.execution.steps.ExecuteWorkBuildOperationFiringStep.execute(ExecuteWorkBuildOperationFiringStep.java:39)
2026-09-24T02:53:47.5790159Z 	at org.gradle.internal.execution.steps.IdentityCacheStep.execute(IdentityCacheStep.java:46)
2026-09-24T02:53:47.5791287Z 	at org.gradle.internal.execution.steps.IdentityCacheStep.execute(IdentityCacheStep.java:34)
2026-09-24T02:53:47.5792346Z 	at org.gradle.internal.execution.steps.IdentifyStep.execute(IdentifyStep.java:56)
2026-09-24T02:53:47.5793532Z 	at org.gradle.internal.execution.steps.IdentifyStep.execute(IdentifyStep.java:38)
2026-09-24T02:53:47.5794614Z 	at org.gradle.internal.execution.impl.DefaultExecutionEngine$1.execute(DefaultExecutionEngine.java:68)
2026-09-24T02:53:47.5795781Z 	at org.gradle.api.internal.tasks.execution.ExecuteActionsTaskExecuter.executeIfValid(ExecuteActionsTaskExecuter.java:132)
2026-09-24T02:53:47.5796602Z 	... 30 more
2026-09-24T02:53:47.5797192Z Caused by: com.android.builder.internal.aapt.v2.Aapt2Exception: Android resource linking failed
2026-09-24T02:53:47.5798945Z app.multisession.browser.app-main-51:/layout/activity_browser.xml:204: error: 'center_end' is incompatible with attribute layout_gravity (attr) flags [bottom=80, center=17, center_horizontal=1, center_vertical=16, clip_horizontal=8, clip_vertical=128, end=8388613, fill=119, fill_horizontal=7, fill_vertical=112, left=3, right=5, start=8388611, top=48].
2026-09-24T02:53:47.5800575Z error: failed linking file resources.
2026-09-24T02:53:47.5800892Z 
2026-09-24T02:53:47.5801317Z 	at com.android.builder.internal.aapt.v2.Aapt2Exception$Companion.create(Aapt2Exception.kt:44)
2026-09-24T02:53:47.5802212Z 	at com.android.builder.internal.aapt.v2.Aapt2Exception$Companion.create$default(Aapt2Exception.kt:33)
2026-09-24T02:53:47.5803105Z 	at com.android.builder.internal.aapt.v2.Aapt2DaemonImpl.doLink(Aapt2DaemonImpl.kt:187)
2026-09-24T02:53:47.5804022Z 	at com.android.builder.internal.aapt.v2.Aapt2Daemon.link(Aapt2Daemon.kt:122)
2026-09-24T02:53:47.5804912Z 	at com.android.builder.internal.aapt.v2.Aapt2DaemonManager$LeasedAaptDaemon.link(Aapt2DaemonManager.kt:164)
2026-09-24T02:53:47.5805937Z 	at com.android.builder.internal.aapt.v2.Aapt2DaemonManager$leasingAapt2Daemon$1.link(Aapt2DaemonManager.kt:188)
2026-09-24T02:53:47.5807072Z 	at com.android.build.gradle.internal.services.PartialInProcessResourceProcessor.link(PartialInProcessResourceProcessor.kt:51)
2026-09-24T02:53:47.5808328Z 	at com.android.build.gradle.internal.res.Aapt2ProcessResourcesRunnableKt.processResources(Aapt2ProcessResourcesRunnable.kt:73)
2026-09-24T02:53:47.5809679Z 	at com.android.build.gradle.internal.res.LinkApplicationAndroidResourcesTask$Companion.invokeAaptForSplit(LinkApplicationAndroidResourcesTask.kt:857)
2026-09-24T02:53:47.5811118Z 	at com.android.build.gradle.internal.res.LinkApplicationAndroidResourcesTask$Companion.access$invokeAaptForSplit(LinkApplicationAndroidResourcesTask.kt:695)
2026-09-24T02:53:47.5812634Z 	at com.android.build.gradle.internal.res.LinkApplicationAndroidResourcesTask$TaskAction.run(LinkApplicationAndroidResourcesTask.kt:396)
2026-09-24T02:53:47.5813962Z 	at com.android.build.gradle.internal.profile.ProfileAwareWorkAction.execute(ProfileAwareWorkAction.kt:66)
2026-09-24T02:53:47.5815129Z 	at org.gradle.workers.internal.DefaultWorkerServer.execute(DefaultWorkerServer.java:68)
2026-09-24T02:53:47.5816247Z 	at org.gradle.workers.internal.NoIsolationWorkerFactory$1$1.create(NoIsolationWorkerFactory.java:64)
2026-09-24T02:53:47.5817412Z 	at org.gradle.workers.internal.NoIsolationWorkerFactory$1$1.create(NoIsolationWorkerFactory.java:61)
2026-09-24T02:53:47.5818612Z 	at org.gradle.internal.classloader.ClassLoaderUtils.executeInClassloader(ClassLoaderUtils.java:102)
2026-09-24T02:53:47.5819865Z 	at org.gradle.workers.internal.NoIsolationWorkerFactory$1.lambda$execute$0(NoIsolationWorkerFactory.java:61)
2026-09-24T02:53:47.5820956Z 	at org.gradle.workers.internal.AbstractWorker$1.call(AbstractWorker.java:44)
2026-09-24T02:53:47.5821857Z 	at org.gradle.workers.internal.AbstractWorker$1.call(AbstractWorker.java:41)
2026-09-24T02:53:47.5823123Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:210)
2026-09-24T02:53:47.5824960Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:205)
2026-09-24T02:53:47.5826450Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:67)
2026-09-24T02:53:47.5827918Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:60)
2026-09-24T02:53:47.5829241Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:167)
2026-09-24T02:53:47.5830577Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:60)
2026-09-24T02:53:47.5831853Z 	at org.gradle.internal.operations.DefaultBuildOperationRunner.call(DefaultBuildOperationRunner.java:54)
2026-09-24T02:53:47.5833094Z 	at org.gradle.workers.internal.AbstractWorker.executeWrappedInBuildOperation(AbstractWorker.java:41)
2026-09-24T02:53:47.5834321Z 	at org.gradle.workers.internal.NoIsolationWorkerFactory$1.execute(NoIsolationWorkerFactory.java:58)
2026-09-24T02:53:47.5835355Z 	at org.gradle.workers.internal.DefaultWorkerExecutor.lambda$submitWork$0(DefaultWorkerExecutor.java:176)
2026-09-24T02:53:47.5836485Z 	at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner.runExecution(DefaultConditionalExecutionQueue.java:194)
2026-09-24T02:53:47.5837707Z 	at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner.access$700(DefaultConditionalExecutionQueue.java:127)
2026-09-24T02:53:47.5838892Z 	at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner$1.run(DefaultConditionalExecutionQueue.java:169)
2026-09-24T02:53:47.5839781Z 	at org.gradle.internal.Factories$1.create(Factories.java:30)
2026-09-24T02:53:47.5840630Z 	at org.gradle.internal.work.DefaultWorkerLeaseService.lambda$withLocksAcquired$0(DefaultWorkerLeaseService.java:275)
2026-09-24T02:53:47.5841612Z 	at org.gradle.internal.work.ResourceLockStatistics$1.measure(ResourceLockStatistics.java:43)
2026-09-24T02:53:47.5842603Z 	at org.gradle.internal.work.DefaultWorkerLeaseService.withLocksAcquired(DefaultWorkerLeaseService.java:273)
2026-09-24T02:53:47.5843857Z 	at org.gradle.internal.work.DefaultWorkerLeaseService.withLocks(DefaultWorkerLeaseService.java:265)
2026-09-24T02:53:47.5845111Z 	at org.gradle.internal.work.DefaultWorkerLeaseService.runAsWorkerThread(DefaultWorkerLeaseService.java:128)
2026-09-24T02:53:47.5846389Z 	at org.gradle.internal.work.DefaultWorkerLeaseService.runAsWorkerThread(DefaultWorkerLeaseService.java:133)
2026-09-24T02:53:47.5847767Z 	at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner.runBatch(DefaultConditionalExecutionQueue.java:164)
2026-09-24T02:53:47.5849384Z 	at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner.run(DefaultConditionalExecutionQueue.java:133)
2026-09-24T02:53:47.5850381Z 	... 2 more
2026-09-24T02:53:47.5851307Z 	Suppressed: java.util.NoSuchElementException: Unable to get absolute path from app.multisession.browser.app-main-51:/layout/activity_browser.xml
2026-09-24T02:53:47.5852555Z                        because app.multisession.browser.app-main-51 is not key in sourceSetPathMap.
2026-09-24T02:53:47.5853971Z 		at com.android.ide.common.resources.RelativeResourceUtils$relativeResourcePathToAbsolutePath$1.invoke(RelativeResourceUtils.kt:93)
2026-09-24T02:53:47.5855495Z 		at com.android.ide.common.resources.RelativeResourceUtils$relativeResourcePathToAbsolutePath$1.invoke(RelativeResourceUtils.kt:69)
2026-09-24T02:53:47.5857004Z 		at com.android.ide.common.resources.RelativeResourceUtils.relativeResourcePathToAbsolutePath(RelativeResourceUtils.kt:62)
2026-09-24T02:53:47.5858377Z 		at com.android.ide.common.blame.parser.aapt.Aapt2ErrorParser$MessageParser.parse(Aapt2ErrorParser.kt:113)
2026-09-24T02:53:47.5859506Z 		at com.android.ide.common.blame.parser.aapt.Aapt2ErrorParser.parse(Aapt2ErrorParser.kt:89)
2026-09-24T02:53:47.5860547Z 		at com.android.ide.common.blame.parser.aapt.Aapt2OutputParser.parse(Aapt2OutputParser.java:56)
2026-09-24T02:53:47.5861692Z 		at com.android.ide.common.blame.parser.ToolOutputParser.parseToolOutput(ToolOutputParser.java:84)
2026-09-24T02:53:47.5862905Z 		at com.android.build.gradle.internal.res.Aapt2ErrorUtils.rewriteException(Aapt2ErrorUtils.kt:189)
2026-09-24T02:53:47.5884594Z 		at com.android.build.gradle.internal.res.Aapt2ErrorUtils.rewriteLinkException(Aapt2ErrorUtils.kt:120)
2026-09-24T02:53:47.5886022Z 		at com.android.build.gradle.internal.res.Aapt2ProcessResourcesRunnableKt.processResources(Aapt2ProcessResourcesRunnable.kt:75)
2026-09-24T02:53:47.5904920Z 		at com.android.build.gradle.internal.res.LinkApplicationAndroidResourcesTask$Companion.invokeAaptForSplit(LinkApplicationAndroidResourcesTask.kt:857)
2026-09-24T02:53:47.5924927Z 		at com.android.build.gradle.internal.res.LinkApplicationAndroidResourcesTask$Companion.access$invokeAaptForSplit(LinkApplicationAndroidResourcesTask.kt:695)
2026-09-24T02:53:47.5944832Z 		at com.android.build.gradle.internal.res.LinkApplicationAndroidResourcesTask$TaskAction.run(LinkApplicationAndroidResourcesTask.kt:396)
2026-09-24T02:53:47.5946543Z 		at com.android.build.gradle.internal.profile.ProfileAwareWorkAction.execute(ProfileAwareWorkAction.kt:66)
2026-09-24T02:53:47.5965608Z 		at org.gradle.workers.internal.DefaultWorkerServer.execute(DefaultWorkerServer.java:68)
2026-09-24T02:53:47.5984566Z 		at org.gradle.workers.internal.NoIsolationWorkerFactory$1$1.create(NoIsolationWorkerFactory.java:64)
2026-09-24T02:53:47.6004652Z 		at org.gradle.workers.internal.NoIsolationWorkerFactory$1$1.create(NoIsolationWorkerFactory.java:61)
2026-09-24T02:53:47.6024575Z 		at org.gradle.internal.classloader.ClassLoaderUtils.executeInClassloader(ClassLoaderUtils.java:102)
2026-09-24T02:53:47.6025953Z 		at org.gradle.workers.internal.NoIsolationWorkerFactory$1.lambda$execute$0(NoIsolationWorkerFactory.java:61)
2026-09-24T02:53:47.6044340Z 		at org.gradle.workers.internal.AbstractWorker$1.call(AbstractWorker.java:44)
2026-09-24T02:53:47.6062838Z 		at org.gradle.workers.internal.AbstractWorker$1.call(AbstractWorker.java:41)
2026-09-24T02:53:47.6084872Z 		at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:210)
2026-09-24T02:53:47.6086516Z 		at org.gradle.internal.operations.DefaultBuildOperationRunner$CallableBuildOperationWorker.execute(DefaultBuildOperationRunner.java:205)
2026-09-24T02:53:47.6104666Z 		at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:67)
2026-09-24T02:53:47.6124579Z 		at org.gradle.internal.operations.DefaultBuildOperationRunner$2.execute(DefaultBuildOperationRunner.java:60)
2026-09-24T02:53:47.6145095Z 		at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:167)
2026-09-24T02:53:47.6164551Z 		at org.gradle.internal.operations.DefaultBuildOperationRunner.execute(DefaultBuildOperationRunner.java:60)
2026-09-24T02:53:47.6165935Z 		at org.gradle.internal.operations.DefaultBuildOperationRunner.call(DefaultBuildOperationRunner.java:54)
2026-09-24T02:53:47.6184623Z 		at org.gradle.workers.internal.AbstractWorker.executeWrappedInBuildOperation(AbstractWorker.java:41)
2026-09-24T02:53:47.6205387Z 		at org.gradle.workers.internal.NoIsolationWorkerFactory$1.execute(NoIsolationWorkerFactory.java:58)
2026-09-24T02:53:47.6224439Z 		at org.gradle.workers.internal.DefaultWorkerExecutor.lambda$submitWork$0(DefaultWorkerExecutor.java:176)
2026-09-24T02:53:47.6244338Z 		at java.base/java.util.concurrent.FutureTask.run(FutureTask.java:264)
2026-09-24T02:53:47.6245697Z 		at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner.runExecution(DefaultConditionalExecutionQueue.java:194)
2026-09-24T02:53:47.6264823Z 		at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner.access$700(DefaultConditionalExecutionQueue.java:127)
2026-09-24T02:53:47.6284759Z 		at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner$1.run(DefaultConditionalExecutionQueue.java:169)
2026-09-24T02:53:47.6304430Z 		at org.gradle.internal.Factories$1.create(Factories.java:30)
2026-09-24T02:53:47.6308862Z 		at org.gradle.internal.work.DefaultWorkerLeaseService.lambda$withLocksAcquired$0(DefaultWorkerLeaseService.java:275)
2026-09-24T02:53:47.6334581Z 		at org.gradle.internal.work.ResourceLockStatistics$1.measure(ResourceLockStatistics.java:43)
2026-09-24T02:53:47.6354736Z 		at org.gradle.internal.work.DefaultWorkerLeaseService.withLocksAcquired(DefaultWorkerLeaseService.java:273)
2026-09-24T02:53:47.6356239Z 		at org.gradle.internal.work.DefaultWorkerLeaseService.withLocks(DefaultWorkerLeaseService.java:265)
2026-09-24T02:53:47.6374726Z 		at org.gradle.internal.work.DefaultWorkerLeaseService.runAsWorkerThread(DefaultWorkerLeaseService.java:128)
2026-09-24T02:53:47.6394597Z 		at org.gradle.internal.work.DefaultWorkerLeaseService.runAsWorkerThread(DefaultWorkerLeaseService.java:133)
2026-09-24T02:53:47.6414776Z 		at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner.runBatch(DefaultConditionalExecutionQueue.java:164)
2026-09-24T02:53:47.6434696Z 		at org.gradle.internal.work.DefaultConditionalExecutionQueue$ExecutionRunner.run(DefaultConditionalExecutionQueue.java:133)
2026-09-24T02:53:47.6436025Z 		at java.base/java.util.concurrent.Executors$RunnableAdapter.call(Executors.java:539)
2026-09-24T02:53:47.6453651Z 		at java.base/java.util.concurrent.FutureTask.run(FutureTask.java:264)
2026-09-24T02:53:47.6476796Z 		at org.gradle.internal.concurrent.ExecutorPolicy$CatchAndRecordFailures.onExecute(ExecutorPolicy.java:64)
2026-09-24T02:53:47.6514249Z 		at org.gradle.internal.concurrent.AbstractManagedExecutor$1.run(AbstractManagedExecutor.java:47)
2026-09-24T02:53:47.6534657Z 		at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1136)
2026-09-24T02:53:47.6563608Z 		at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:635)
2026-09-24T02:53:47.6564834Z 		at java.base/java.lang.Thread.run(Thread.java:840)
2026-09-24T02:53:47.6584030Z 
2026-09-24T02:53:47.6604011Z 
2026-09-24T02:53:47.6604389Z BUILD FAILED in 40s
2026-09-24T02:53:47.8973338Z ##[error]Process completed with exit code 1.
2026-09-24T02:53:47.9162984Z Post job cleanup.
2026-09-24T02:53:48.1251876Z In post-action step
2026-09-24T02:53:48.1284743Z ##[group]Stopping Gradle daemons
2026-09-24T02:53:48.1314999Z Stopping Gradle daemons for /home/runner/work/_temp/.gradle-actions/gradle-installations/installs/gradle-9.4.1
2026-09-24T02:53:48.1316557Z [command]/home/runner/work/_temp/.gradle-actions/gradle-installations/installs/gradle-9.4.1/bin/gradle --stop
2026-09-24T02:53:48.8263663Z No Gradle daemons are running.
2026-09-24T02:53:48.8441215Z ##[endgroup]
2026-09-24T02:53:48.8442840Z Not performing cache-cleanup due to build failure
2026-09-24T02:53:48.8444066Z ##[group]Caching Gradle state
2026-09-24T02:53:49.2567023Z [command]/usr/bin/tar --posix -cf cache.tzst --exclude cache.tzst -P -C /home/runner/work/MultiSessionBrowser/MultiSessionBrowser --files-from manifest.txt --use-compress-program zstdmt
2026-09-24T02:53:49.8644411Z Sent 90159 of 90159 (100.0%), 0.3 MBs/sec
2026-09-24T02:53:50.1060699Z Saved cache entry with key gradle-kotlin-dsl-v1-2bd8b12a9247cf81d3fa3d0fb9d4c55a from /home/runner/.gradle/caches/*/kotlin-dsl/accessors/*/,/home/runner/.gradle/caches/*/kotlin-dsl/scripts/*/ in 910ms
2026-09-24T02:53:50.3414558Z [command]/usr/bin/tar --posix -cf cache.tzst --exclude cache.tzst -P -C /home/runner/work/MultiSessionBrowser/MultiSessionBrowser --files-from manifest.txt --use-compress-program zstdmt
2026-09-24T02:53:55.1722567Z Sent 24701914 of 360246234 (6.9%), 23.6 MBs/sec
2026-09-24T02:53:56.0264950Z Sent 360246234 of 360246234 (100.0%), 185.3 MBs/sec
2026-09-24T02:53:56.2925760Z Saved cache entry with key gradle-transforms-v1-6421adb98757d4a81ba1fd8f02f80ece from /home/runner/.gradle/caches/transforms-4/*/,/home/runner/.gradle/caches/*/transforms/*/ in 6602ms
2026-09-24T02:53:58.3594308Z [command]/usr/bin/tar --posix -cf cache.tzst --exclude cache.tzst -P -C /home/runner/work/MultiSessionBrowser/MultiSessionBrowser --files-from manifest.txt --use-compress-program zstdmt
2026-09-24T02:53:59.7518313Z Sent 41484288 of 80011235 (51.8%), 39.6 MBs/sec
2026-09-24T02:54:00.3504606Z Sent 80011235 of 80011235 (100.0%), 47.8 MBs/sec
2026-09-24T02:54:00.5894364Z Saved cache entry with key gradle-home-v1|Linux-X64|build[620c74083efa5b88ef904c2356f72d31]-d1ef99e61703ec4af135158340a820e9eadd24ff from /home/runner/.gradle/caches,/home/runner/.gradle/notifications,/home/runner/.gradle/.setup-gradle in 2245ms
2026-09-24T02:54:00.5896080Z ##[endgroup]
2026-09-24T02:54:00.5901063Z Generating Job Summary
2026-09-24T02:54:00.5913468Z Completed post-action step
2026-09-24T02:54:00.6124096Z Post job cleanup.
2026-09-24T02:54:00.7272470Z (node:2451) [DEP0040] DeprecationWarning: The `punycode` module is deprecated. Please use a userland alternative instead.
2026-09-24T02:54:00.7274071Z (Use `node --trace-deprecation ...` to show where the warning was created)
2026-09-24T02:54:00.7888466Z Post job cleanup.
2026-09-24T02:54:00.8872488Z [command]/usr/bin/git version
2026-09-24T02:54:00.8944148Z git version 2.55.0
2026-09-24T02:54:00.8997279Z Temporarily overriding HOME='/home/runner/work/_temp/5347bed0-cd1b-488b-8d19-7b94db5b3721' before making global git config changes
2026-09-24T02:54:00.9003378Z Adding repository directory to the temporary git global config as a safe directory
2026-09-24T02:54:00.9004948Z [command]/usr/bin/git config --global --add safe.directory /home/runner/work/MultiSessionBrowser/MultiSessionBrowser
2026-09-24T02:54:00.9086731Z [command]/usr/bin/git config --local --name-only --get-regexp core\.sshCommand
2026-09-24T02:54:00.9149499Z [command]/usr/bin/git submodule foreach --recursive sh -c "git config --local --name-only --get-regexp 'core\.sshCommand' && git config --local --unset-all 'core.sshCommand' || :"
2026-09-24T02:54:00.9735916Z [command]/usr/bin/git config --local --name-only --get-regexp http\.https\:\/\/github\.com\/\.extraheader
2026-09-24T02:54:00.9737420Z http.https://github.com/.extraheader
2026-09-24T02:54:00.9739600Z [command]/usr/bin/git config --local --unset-all http.https://github.com/.extraheader
2026-09-24T02:54:00.9742747Z [command]/usr/bin/git submodule foreach --recursive sh -c "git config --local --name-only --get-regexp 'http\.https\:\/\/github\.com\/\.extraheader' && git config --local --unset-all 'http.https://github.com/.extraheader' || :"
2026-09-24T02:54:01.0028335Z [command]/usr/bin/git config --local --name-only --get-regexp ^includeIf\.gitdir:
2026-09-24T02:54:01.0082595Z [command]/usr/bin/git submodule foreach --recursive git config --local --show-origin --name-only --get-regexp remote.origin.url
2026-09-24T02:54:01.0425614Z Cleaning up orphan processes
2026-09-24T02:54:01.0655696Z ##[warning]Node.js 20 is deprecated. The following actions target Node.js 20 but are being forced to run on Node.js 24: actions/checkout@v4, actions/setup-java@v4, gradle/actions/setup-gradle@v4. For more information see: https://github.blog/changelog/2025-09-19-deprecation-of-node-20-on-github-actions-runners/
```
