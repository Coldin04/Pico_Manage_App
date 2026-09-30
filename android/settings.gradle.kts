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
val picobookSdkVersion = providers.gradleProperty("picobookSdkVersion").orNull.orEmpty()
val isGitSdkVersion = Regex("^git\\.[0-9a-f]{40}$").matches(picobookSdkVersion)
if (picobookSdkVersion.startsWith("git.") && !isGitSdkVersion) {
    throw GradleException("PicoBook SDK git versions must use git.<full 40-character lowercase commit SHA>.")
}
val sdkFromLocalMaven = picobookSdkVersion == "local" || isGitSdkVersion

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (sdkFromLocalMaven) {
            // Source-built coordinates must come from mavenLocal and never fall back to Central.
            mavenLocal {
                content { includeModule("com.cold04", "picobookmgr") }
            }
            google {
                content { excludeModule("com.cold04", "picobookmgr") }
            }
            mavenCentral {
                content { excludeModule("com.cold04", "picobookmgr") }
            }
        } else {
            google()
            mavenCentral()
        }
    }
}

rootProject.name = "Pico Manage"
include(":app")
