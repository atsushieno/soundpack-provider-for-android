plugins {
    alias(libs.plugins.android.application)
}

// A native app that uses the C API (soundpack.h, soundpack_host.h) through the prefab package,
// as a JUCE or SDL app would: C++ built with c++_shared, linking soundpack-native::soundpack.
// Its device tests are the Phase 5 checks of the C API.
android {
    namespace = "org.androidaudioplugin.soundpack.sample.nativeclient"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()
    defaultConfig {
        applicationId = "org.androidaudioplugin.soundpack.sample.nativeclient"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
    buildFeatures {
        prefab = true
    }
    // The device test copies the sample SoundFonts into a folder it links.
    sourceSets.getByName("androidTest").assets.directories.add("../sample-sf2-pack/src/main/assets/sf2")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":soundpack-native"))

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
