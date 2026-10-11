pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AntCity"
include(":core")
include(":server")
include(":lab")
// The Android app needs the Android SDK; skip it with -PnoApp (e.g. when building only the server).
if (!providers.gradleProperty("noApp").isPresent) {
    include(":app")
    // Shared local multiplayer (git submodule, see powerlink/README.md).
    include(":powerlink")
}
