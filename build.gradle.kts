// Top-level build file.
// Toolchain (verified together in docs/GECKOVIEW_MIGRATION_PREP.md §4.2, "Mozilla-aligned"):
//   Gradle 9.4.1 <-> AGP 9.2.1 (built-in Kotlin, no org.jetbrains.kotlin.android plugin)
//   <-> JDK 17 <-> KSP 2.3.12 <-> Room 2.8.5 <-> GeckoView 155.0.20260903215306
plugins {
    id("com.android.application") version "9.2.1" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
}
