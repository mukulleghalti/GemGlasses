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
                                        mavenCentral() // The Meta SDK is now here!
                }
}

rootProject.name = "GemGlasses"
include(":app")
            }
}