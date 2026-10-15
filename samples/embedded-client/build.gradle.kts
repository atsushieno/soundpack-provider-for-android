plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// An app that embeds the host core: it links its own folders, with no Soundpack Manager
// installed (docs/PLAN.md D1, Phase 2 gate).
android {
    namespace = "org.androidaudioplugin.soundpack.sample.embedded"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "org.androidaudioplugin.soundpack.sample.embedded"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        compose = true
    }
    // The device test copies the sample SoundFonts into a folder it links.
    sourceSets.getByName("androidTest").assets.directories.add("../sample-sf2-pack/src/main/assets/sf2")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":soundpack-host-ui"))
    implementation(project(":soundpack-ui-compose"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)

    androidTestImplementation(project(":soundpack-testkit"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
