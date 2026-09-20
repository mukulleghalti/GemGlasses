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
                                                
                                                        val localProps = java.util.Properties()
                                                                val f = rootDir.resolve("local.properties")
                                                                        if (f.exists()) f.inputStream().use { localProps.load(it) }

                                                                                val githubToken = localProps.getProperty("github_token") ?: System.getenv("GITHUB_TOKEN")
                                                                                        val githubActor = localProps.getProperty("github_actor") ?: System.getenv("GITHUB_ACTOR")

                                                                                                if (!githubToken.isNullOrBlank()) {
                                                                                                                    maven {
                                                                                                                                        name = "MetaWearables"
                                                                                                                                                        // Using the exact URL confirmed by your curl success
                                                                                                                                                                        url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
                                                                                                                                                                                        credentials {
                                                                                                                                                                                                                    username = githubActor ?: "gemglasses"
                                                                                                                                                                                                                                        password = githubToken
                                                                                                                                                                                        }
                                                                                                                    }
                                                                                                }
                }
}

rootProject.name = "GemGlasses"
include(":app")