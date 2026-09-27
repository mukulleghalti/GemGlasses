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
                                /*
                                 * Vendored Google Home APIs SDK: Google does
                                 * not publish play-services-home{,-types} on
                                 * public Maven repos, so the AARs (downloaded
                                 * from the Google Home developer console,
                                 * sign-in required) are hosted here in a
                                 * standard local Maven layout. See
                                 * mavenLocal/README.md.
                                 */
                                maven(url = uri("mavenLocal"))
            }
}

rootProject.name = "Veyra"
include(":app")