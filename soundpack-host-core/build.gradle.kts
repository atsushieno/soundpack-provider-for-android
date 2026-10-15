plugins {
    alias(libs.plugins.android.library)
}

// The folder machinery shared by the hub and by apps that embed it (docs/PLAN.md §3.9):
// no Compose, no Room, no coroutines.
android {
    namespace = "org.androidaudioplugin.soundpack.host"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":soundpack-provider-sdk"))
}
