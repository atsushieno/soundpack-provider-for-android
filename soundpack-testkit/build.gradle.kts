plugins {
    alias(libs.plugins.android.library)
}

// Used from device tests (androidTest) of providers and clients, here and in pack repositories.
android {
    namespace = "org.androidaudioplugin.soundpack.testkit"
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
    api(project(":soundpack-contract"))
}
