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
        maven {
            url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
            credentials {
                username = System.getenv("GITHUB_ACTOR") ?: "gemglasses"
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
rootProject.name = "GemGlasses"
include(":app")
