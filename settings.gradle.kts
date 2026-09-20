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
                                                
                                                        // This is the essential block for the Meta SDK
                                                                val localProps = java.util.Properties()
                                                                        val f = rootDir.resolve("local.properties")
                                                                                if (f.exists()) f.inputStream().use { localProps.load(it) }

                                                                                        val githubToken = localProps.getProperty("github_token") ?: System.getenv("GITHUB_TOKEN")
                                                                                                val githubActor = localProps.getProperty("github_actor") ?: System.getenv("GITHUB_ACTOR") ?: "gemglasses"

                                                                                                        if (!githubToken.isNullOrBlank()) {
                                                                                                                            maven {
                                                                                                                                                name = "MetaWearablesDAT"
                                                                                                                                                                url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
                                                                                                                                                                                credentials {
                                                                                                                                                                                                            username = githubActor
                                                                                                                                                                                                                                password = githubToken
                                                                                                                                                                                }
                                                                                                                            }
                                                                                                        }
                }
}

rootProject.name = "GemGlasses"
include(":app")