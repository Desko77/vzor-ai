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
    repositories {
        google()
        mavenCentral()
        // Meta Wearables Device Access Toolkit
        maven {
            url = uri("https://maven.pkg.github.com/anthropics/meta-wearables-dat-android")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_USER") ?: ""
                password = providers.gradleProperty("gpr.token").orNull
                    ?: System.getenv("GITHUB_TOKEN") ?: ""
            }
            content {
                includeGroupByRegex("com\\.meta\\..*")
            }
        }
    }
}

rootProject.name = "vzor-ai"
include(":app")
