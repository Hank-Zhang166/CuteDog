pluginManagement {
    repositories {
        google()
        // Hosted runners can share the same Maven Central rate limit.
        if (System.getenv("GITHUB_ACTIONS") == "true")
            maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        if (System.getenv("GITHUB_ACTIONS") == "true")
            maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
rootProject.name = "CuteDog"
include(":app")
