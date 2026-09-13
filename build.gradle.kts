// Top-level build file.
// Toolchain (verified together — see BUILD_FIX_CONTINUATION.md and docs/GECKOVIEW_MIGRATION_PREP.md §4.2):
//   Gradle 9.4.1 <-> AGP 9.2.1 (built-in Kotlin) <-> Kotlin Gradle plugin 2.4.20 <-> JDK 17
//   <-> KSP 2.3.12 <-> Room 2.8.5 <-> GeckoView 155.0.20260903215306 (compiled with Kotlin 2.4.10)
//
// WHY the buildscript block below exists:
//   AGP 9.x compiles Kotlin itself ("built-in Kotlin") using whatever Kotlin Gradle plugin (KGP) is on
//   the build classpath. AGP 9.2.1's POM pins KGP 2.2.10 — a Kotlin 2.2 compiler cannot read the
//   Kotlin 2.4 class metadata shipped by GeckoView 155 (kotlin-stdlib 2.4.10):
//     "Module was compiled with an incompatible version of Kotlin. The binary version of its
//      metadata is 2.4.0, expected version is 2.3.0."
//   The supported fix (Android Gradle plugin 9.0 release notes, "Upgrade to a higher KGP version")
//   is to declare the newer KGP on the classpath; Gradle's conflict resolution then upgrades the
//   built-in compiler to it. KGP 2.4.20 officially supports AGP 8.5.2–9.3.1 and Gradle up to 9.7.0.
//   Do NOT replace this with -Xskip-metadata-version-check.
buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.2.1" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
