plugins {
    alias(libs.plugins.android.library)
}

// The C API (soundpack.h, soundpack_host.h; docs/PLAN.md §4), shipped as a prefab package:
// consumers enable `buildFeatures { prefab = true }` and use
// `find_package(soundpack-native REQUIRED CONFIG)` and `soundpack-native::soundpack` in CMake.
// It needs host-core for the folder functions, so it is a module of its own (§4, dependencies).
android {
    namespace = "org.androidaudioplugin.soundpack.ndk"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()
    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
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
        prefabPublishing = true
    }
    prefab {
        create("soundpack") {
            headers = "src/main/cpp/include"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":soundpack-client"))
    api(project(":soundpack-host-core"))
}
