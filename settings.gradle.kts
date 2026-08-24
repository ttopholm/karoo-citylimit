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

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // karoo-ext is mirrored here as com.github.hammerheadnav:karoo-ext and needs no credentials.
        // Hammerhead also publishes it to GitHub Packages as io.hammerhead:karoo-ext, but that
        // repository requires a personal access token even for public artifacts.
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroup("com.github.hammerheadnav")
            }
        }
    }
}

rootProject.name = "karoo-citylimit"
include(":app", ":core")
