plugins {
    alias(libs.plugins.android.library)
}

// Pure Kotlin on purpose (no android.* imports), so it can become a multiplatform module
// later (docs/PLAN.md, D3). It is an Android library only to keep the build simple.
android {
    namespace = "org.androidaudioplugin.soundpack.contract"
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
    testImplementation(libs.junit)
}
