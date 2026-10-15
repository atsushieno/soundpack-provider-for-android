plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.androidaudioplugin.soundpack.sample.sfz"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "org.androidaudioplugin.soundpack.sample.sfz"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }
    // Pack assets are served as descriptor ranges of the APK, so they must not be compressed.
    androidResources {
        noCompress += listOf("sfz", "wav")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":soundpack-provider-sdk"))
}
