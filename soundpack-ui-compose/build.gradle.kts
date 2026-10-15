plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
}

// Compose pieces for soundpack clients: the entry picker (docs/PLAN.md §4).
android {
    namespace = "org.androidaudioplugin.soundpack.ui"
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
    api(project(":soundpack-client"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.coroutines.android)
}
