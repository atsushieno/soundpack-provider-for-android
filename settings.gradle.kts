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

// Libraries (docs/PLAN.md §4).
include(":soundpack-contract", ":soundpack-provider-sdk", ":soundpack-client", ":soundpack-testkit")
include(":soundpack-host-core", ":soundpack-host-ui", ":soundpack-ui-compose", ":soundpack-native")

// Sample pack APKs, one per built-in kind.
include(":samples:sample-sfz-pack", ":samples:sample-sf2-pack", ":samples:sample-jsfx-pack")
include(":samples:embedded-client", ":samples:native-client")

// The Soundpack Manager (the hub).
include(":app")

// Phase 0 platform spikes (docs/spikes.md). Throwaway: delete them once the contract is
// written down.
include(":spikes:common", ":spikes:client", ":spikes:hub", ":spikes:pack", ":spikes:invisible")
