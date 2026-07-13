import java.util.Properties

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

        // Meta Wearables Device Access Toolkit is published on GitHub Packages.
        // Provide a classic PAT with `read:packages` scope in local.properties as
        // `github_token` (and optionally `github_actor`). See README > Setup.
        val localProps = Properties().apply {
            val f = rootDir.resolve("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        val githubToken = (localProps.getProperty("github_token")
            ?: System.getenv("GITHUB_TOKEN"))
        val githubActor = (localProps.getProperty("github_actor")
            ?: System.getenv("GITHUB_ACTOR") ?: "gemglasses")

        if (!githubToken.isNullOrBlank()) {
            maven {
                name = "MetaWearablesDAT"
                url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
                credentials {
                    username = githubActor
                    password = githubToken
                }
            }
        } else {
            logger.warn(
                "[GemGlasses] No github_token found — the Meta DAT SDK repository is disabled. " +
                    "The app builds against the mock glasses backend only. See README > Setup."
            )
        }
    }
}

rootProject.name = "GemGlasses"
include(":app")
