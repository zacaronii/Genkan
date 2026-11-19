import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "app.genkan"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "app.genkan"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
        ndk {
            // The Thor and the arm64 test emulator.
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(21)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.tv.foundation)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp)
    implementation(libs.moshi)
    ksp(libs.moshi.codegen)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.zxing.core)
    implementation(libs.androidx.webkit)
    implementation(libs.mlkit.text.japanese)
    implementation(libs.onnxruntime.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

/**
 * Meiki OCR's models (LGPL-3.0, trained on Japanese games) are bundled, not committed: this
 * fetches the pinned upstream ONNX files once into .gradle/meiki, checks their SHA-256, and adds
 * them to the APK's assets. Bump a revision only after checking the new files on the Thor.
 */
abstract class DownloadMeikiModels : DefaultTask() {
    /** Each entry is "url sha256 assetName". */
    @get:Input abstract val models: ListProperty<String>
    @get:Internal abstract val cacheDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val out = outputDir.get().asFile.resolve("meiki").apply { deleteRecursively(); mkdirs() }
        val cache = cacheDir.get().asFile.apply { mkdirs() }
        for (entry in models.get()) {
            val (url, sha, name) = entry.split(" ")
            val cached = cache.resolve(name)
            if (!cached.isFile || sha256(cached) != sha) {
                logger.lifecycle("Downloading Meiki model $name")
                val part = cache.resolve("$name.part")
                URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
                check(sha256(part) == sha) { "$name: SHA-256 mismatch for $url" }
                part.renameTo(cached)
            }
            cached.copyTo(out.resolve(name), overwrite = true)
        }
    }

    private fun sha256(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

val meikiDetect = "https://huggingface.co/rtr46/meiki.text.detect.v0/resolve/a9cffa4f60cbf72ddb87edf19c6f98a01cd042e6"
val meikiRecognize = "https://huggingface.co/rtr46/meiki.txt.recognition.v0/resolve/a28cf5874dc2438ebb1c86336be26bcec51e3375"

val downloadMeikiModels = tasks.register<DownloadMeikiModels>("downloadMeikiModels") {
    models.set(
        listOf(
            "$meikiDetect/meiki.text.detect.v0.1.960x544.onnx 40b6a016667745cae7d3055929ae3b8b1e7716aac795f5904cd3c2c7c3b8404b detect.onnx",
            "$meikiRecognize/meiki.text.rec.v0.960x32.onnx 3e96bc772fbee9717e536a6353032bb944c3382dd2f6960ef4890decda43b000 rec_horizontal.onnx",
            "$meikiRecognize/meiki.text.rec.v0.vertical.32x480.onnx 2c2a83a23bc3b7e6c63962175f507ecc6c5e85cc174f17bdec37d9bbd0bf895a rec_vertical.onnx",
        ),
    )
    cacheDir.set(rootProject.layout.projectDirectory.dir(".gradle/meiki"))
    outputDir.set(layout.buildDirectory.dir("generated/meiki"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(downloadMeikiModels, DownloadMeikiModels::outputDir)
    }
}
