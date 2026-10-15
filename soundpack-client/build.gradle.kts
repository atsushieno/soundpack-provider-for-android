plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.androidaudioplugin.soundpack.client"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":soundpack-contract"))
    implementation(libs.coroutines.android)

    // The device tests read the sample packs (samples/) installed on the device.
    androidTestImplementation(project(":soundpack-testkit"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
