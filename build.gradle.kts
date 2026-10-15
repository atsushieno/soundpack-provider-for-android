plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
}

// Libraries are published under one group and version. Until the V1 freeze (docs/PLAN.md,
// Phase 7) they go to Maven Local only, for the pack and plugin repositories to build against:
//   ./gradlew publishToMavenLocal
val soundpackVersion = "0.1.0-dev"

subprojects {
    group = "org.androidaudioplugin.soundpack"
    version = soundpackVersion
    if (name.startsWith("soundpack-")) {
        apply(plugin = "maven-publish")
        plugins.withId("com.android.library") {
            extensions.configure<com.android.build.api.dsl.LibraryExtension> {
                publishing {
                    singleVariant("release") { withSourcesJar() }
                }
            }
            afterEvaluate {
                extensions.configure<PublishingExtension> {
                    publications {
                        create<MavenPublication>("release") {
                            from(components["release"])
                        }
                    }
                }
            }
        }
    }
}
