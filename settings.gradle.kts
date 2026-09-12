pluginManagement {
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id.startsWith("org.jetbrains.kotlin")) {
                useVersion("2.3.12")
            }
        }
    }
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Mozilla GeckoView (org.mozilla.geckoview:*). No credentials required.
        maven { url = uri("https://maven.mozilla.org/maven2/") }
    }
}
rootProject.name = "MultiSessionBrowser"
include(":app")
