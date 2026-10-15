import java.util.Random

plugins {
    alias(libs.plugins.android.application)
}

/** S3 needs an uncompressed asset of known content: 3 MiB plus an odd tail, so that neither
 * its offset inside the APK nor its length is page-aligned. */
abstract class GenerateSpikeAsset : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val file = outputDirectory.file("pack/sample.bin").get().asFile
        file.parentFile.mkdirs()
        val bytes = ByteArray(3 * 1024 * 1024 + 123).also { Random(0x5EED).nextBytes(it) }
        file.writeBytes(bytes)
    }
}

val generateSpikeAsset = tasks.register<GenerateSpikeAsset>("generateSpikeAsset")

android {
    namespace = "org.androidaudioplugin.soundpack.spike.pack"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "org.androidaudioplugin.soundpack.spike.pack"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.0.1"
    }
    androidResources {
        noCompress += listOf("bin")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(generateSpikeAsset, GenerateSpikeAsset::outputDirectory)
    }
}

dependencies {
    implementation(project(":spikes:common"))
}
