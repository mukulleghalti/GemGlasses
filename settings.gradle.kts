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
                                mavenCentral() // <- This is all you need for DAT 0.9.0!
            }
}

rootProject.name = "Veyra"
include(":app")