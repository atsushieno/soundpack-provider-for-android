plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// The Soundpack Manager (the hub, docs/PLAN.md §3.6, §3.9): the host core behind exported,
// consent-gated providers, plus consent, sharing and "Connected apps".
android {
    namespace = "org.androidaudioplugin.soundpack.manager"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "org.androidaudioplugin.soundpack.manager"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures {
        compose = true
    }
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
}
