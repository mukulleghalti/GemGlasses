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
                                mavenCentral() // This is now the only one you need for the Meta SDK!
            }
}
rootProject.name = "GemGlasses"
include(":app")
            }
}