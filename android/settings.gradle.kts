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
val inkreaderlinkSdkVersion = providers.gradleProperty("inkreaderlinkSdkVersion").orNull.orEmpty()
val isGitSdkVersion = Regex("^git\\.[0-9a-f]{40}$").matches(inkreaderlinkSdkVersion)
if (inkreaderlinkSdkVersion.startsWith("git.") && !isGitSdkVersion) {
    throw GradleException("InkReaderLink git versions must use git.<full 40-character lowercase commit SHA>.")
}
val sdkFromLocalMaven = inkreaderlinkSdkVersion == "local" || isGitSdkVersion

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (sdkFromLocalMaven) {
            // Source-built coordinates must come from mavenLocal and never fall back to Central.
            mavenLocal {
                content { includeModule("com.cold04", "inkreaderlink-uniffi") }
            }
            google {
                content { excludeModule("com.cold04", "inkreaderlink-uniffi") }
            }
            mavenCentral {
                content { excludeModule("com.cold04", "inkreaderlink-uniffi") }
            }
        } else {
            google()
            mavenCentral()
        }
    }
}

rootProject.name = "Pico Manage"
include(":app")
