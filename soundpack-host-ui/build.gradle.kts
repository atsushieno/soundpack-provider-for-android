plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

// Optional Compose folder management on top of the UI-free host API (docs/PLAN.md §3.9).
android {
    namespace = "org.androidaudioplugin.soundpack.host.ui"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
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
    api(project(":soundpack-host-core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.coroutines.android)
}
