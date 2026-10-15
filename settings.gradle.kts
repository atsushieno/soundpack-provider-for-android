pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "soundpack-provider-for-android"

// Phase 0 platform spikes (docs/PLAN.md §6). Throwaway: they are deleted once the
// contract is written down.
include(":spikes:common", ":spikes:client", ":spikes:hub", ":spikes:pack", ":spikes:invisible")
