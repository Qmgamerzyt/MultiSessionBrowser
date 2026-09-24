import com.android.build.api.variant.FilterConfiguration

plugins {
    id("com.android.application")   // AGP 9: Kotlin is compiled by the built-in Kotlin support (no kotlin-android plugin)
    id("com.google.devtools.ksp")
}

// ---------------------------------------------------------------------------------------------
// GeckoView (Mozilla Firefox engine) — THE single place where the engine version is defined.
// To update: change this string to a version listed at
//   https://maven.mozilla.org/?prefix=maven2/org/mozilla/geckoview/geckoview/
// then re-check its POM for transitive minimums (Kotlin / androidx.core / compileSdk), see
// docs/GECKOVIEW_MIGRATION.md. Never use a dynamic range (155.+): pin the exact build id.
// ---------------------------------------------------------------------------------------------
val geckoViewVersion = "155.0.20260903215306"

android {
    namespace = "app.multisession.browser"
    // GeckoView 155's androidx.core 1.19 requires >= API 36.1; Mozilla builds GeckoView 155 against 37.1.
    // API 37 is published ONLY as minor-versioned SDK platforms ("platforms;android-37.0" / "android-37.1");
    // without compileSdkMinor AGP looks for a plain "android-37" and fails with
    // "Failed to find target with hash string 'android-37'". compileSdkMinor needs AGP >= 9.1.
    // The CI workflow installs "platforms;android-37.1" to match.
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "app.multisession.browser"
        minSdk = 28                 // Android 9 (Pie). GeckoView >= 144 itself needs 26.
        targetSdk = 35
        versionCode = 14
        versionName = "2.1.6"
        vectorDrawables.useSupportLibrary = true
    }

    // One APK per device ABI (~90 MB each) instead of one fat APK (~175 MB).
    // Play Store / AAB builds ignore splits and do their own per-device slicing.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    // Release signing: uses the ONE stable keystore provided by the CI secrets / env vars. Every APK
    // shipped by CI (debug AND release, both ABIs) must carry this single key: Android only allows an
    // update to install over an existing app when both APKs have the IDENTICAL signer, so a per-build
    // key would force users to uninstall before every update (INSTALL_FAILED_UPDATE_INCOMPATIBLE).
    // CI fails the run when the keystore secret is missing AND re-verifies every built APK's certificate
    // against signing/pinned-signer.sha256, so a drifting key can never ship. Local builds without the
    // env vars fall back to the default debug key (dev-only, never released).
    // See BUGFIX_UPDATE_SIGNATURE.md for the evidence that all pre-2.1.4 builds used throwaway keys.
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("KEYSTORE_FILE")
            if (!ksPath.isNullOrBlank() && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storeType = "PKCS12" // the stable key is a .p12; be explicit instead of relying on defaults
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }
    val releaseSigning = signingConfigs.getByName("release")

    buildTypes {
        release {
            // Shrinking stays off for the engine-migration release so the CI build is deterministic.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseSigning.storeFile != null) releaseSigning else signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            // Same stable key as release so a debug APK installs over a release install (and vice versa).
            signingConfig = if (releaseSigning.storeFile != null) releaseSigning else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Built-in Kotlin aligns its jvmTarget with targetCompatibility automatically.
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        jniLibs.useLegacyPackaging = false
    }
    lint {
        abortOnError = false
    }
}

// Distinct versionCode per ABI split (required if both APKs are ever uploaded to a store).
// arm64-v8a gets the higher code so a 64-bit device prefers it. AAB builds keep the base code.
// APK file names carry versionName + ABI + build type (e.g. MultiSessionBrowser-2.1.2-arm64-v8a-release.apk), so an
// artifact or GitHub release can never be mistaken for a build of another version.
val abiVersionCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2)
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.firstOrNull { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
            val base = output.versionCode.orNull ?: 1
            output.versionCode.set(base * 10 + (abiVersionCodes[abi] ?: 0))
            val versionName = output.versionName.orNull ?: "unknown"
            output.outputFileName.set("MultiSessionBrowser-$versionName-${abi ?: "universal"}-${variant.name}.apk")
        }
    }
}

dependencies {
    // Real Mozilla GeckoView engine (Gecko + SpiderMonkey + Necko), resolved from maven.mozilla.org.
    implementation("org.mozilla.geckoview:geckoview:$geckoViewVersion")

    // AndroidX — versions match Mozilla's own catalog for this GeckoView release (see prep doc §3).
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("com.google.android.material:material:1.14.0")

    // Persistence (Room 2.7+: coroutine/Flow support lives in room-runtime; room-ktx is obsolete)
    implementation("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
