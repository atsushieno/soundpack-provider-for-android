plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "org.androidaudioplugin.soundpack.spike.hub"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "org.androidaudioplugin.soundpack.spike.hub"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // S2 checks that grants survive a hub upgrade: build again with -PspikeVersionCode=2
        // and `adb install -r` the result.
        versionCode = (findProperty("spikeVersionCode") as String?)?.toInt() ?: 1
        versionName = "0.0.$versionCode"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":spikes:common"))
}
