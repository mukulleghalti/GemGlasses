pluginManagement {
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

        // Meta Wearables DAT SDK GitHub Packages Repository
                maven {
                                url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
                                            credentials {
                                                                username = System.getenv("GITHUB_ACTOR") ?: providers.gradleProperty("github_actor").orNull ?: ""
                                                                                password = System.getenv("GITHUB_TOKEN") ?: providers.gradleProperty("github_token").orNull ?: ""
                                            }
                }
    }
}

rootProject.name = "GemGlasses"
include(":app")
