import org.gradle.process.ExecOperations
import javax.inject.Inject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.roborazzi)
}

android {
    // The layout conformance vectors run on the device too, from the instrumented test's assets.
    sourceSets.getByName("androidTest").assets.srcDir("src/test/resources")
    namespace = "app.genkan"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "app.genkan"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1"
        // Instrumented tests: what must hold on the device's own libraries (its charset decoders).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The shelf's per-step trace (ui/ShelfTrace.kt): build with -Pgenkan.shelfTrace=true.
        val shelfTrace = providers.gradleProperty("genkan.shelfTrace").orNull == "true"
        buildConfigField("boolean", "SHELF_TRACE", shelfTrace.toString())
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
        aidl = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric needs the merged resources and manifest for the screenshot tests.
        unitTests.isIncludeAndroidResources = true
    }
}

// Screenshot tests record here (./gradlew :app:recordRoborazziDebug); see the README.
roborazzi {
    outputDir.set(file("src/test/screenshots"))
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
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(project(":libretrodroid"))
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.org.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}

/**
 * Large pinned files that are bundled, not committed: fetched once into a cache under .gradle,
 * checked against their SHA-256, and copied into [subdir] of a generated source folder. Bump a
 * revision only after checking the new files on the Thor.
 */
abstract class DownloadPinnedFiles : DefaultTask() {
    /** Each entry is "url sha256 fileName". */
    @get:Input abstract val files: ListProperty<String>
    @get:Input abstract val subdir: Property<String>
    @get:Internal abstract val cacheDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val out = outputDir.get().asFile.resolve(subdir.get()).apply { deleteRecursively(); mkdirs() }
        val cache = cacheDir.get().asFile.apply { mkdirs() }
        for (entry in files.get()) {
            val (url, sha, name) = entry.split(" ")
            val cached = cache.resolve(name)
            if (!cached.isFile || sha256(cached) != sha) {
                logger.lifecycle("Downloading $name")
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

/**
 * A libretro core built from source, a submodule pinned at [commit] (checked before building), with
 * the NDK's ndk-build into [outputDir]. Built once per commit into [cacheDir], so later builds (and
 * CI, which caches the folder) only copy it; nothing is downloaded but the submodule itself.
 */
abstract class BuildLibretroCore : DefaultTask() {
    @get:Input abstract val commit: Property<String>
    /** The folder holding the core's jni/Android.mk and jni/Application.mk, inside the submodule. */
    @get:Internal abstract val projectDir: DirectoryProperty
    @get:Internal abstract val submoduleDir: DirectoryProperty
    @get:Input abstract val libraryName: Property<String>
    @get:Input abstract val abi: Property<String>
    @get:Input abstract val flags: ListProperty<String>
    /** Further ndk-build variables, "NAME=value" (a core's build profile). */
    @get:Input abstract val variables: ListProperty<String>
    @get:Internal abstract val ndkDir: DirectoryProperty
    @get:Internal abstract val cacheDir: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun build() {
        val head = ByteArrayOutputStream().also { out ->
            exec.exec { commandLine("git", "-C", submoduleDir.get().asFile.path, "rev-parse", "HEAD"); standardOutput = out }
        }.toString().trim()
        check(head == commit.get()) { "${submoduleDir.get().asFile.name} is at $head, not the pinned ${commit.get()}: run git submodule update" }
        val cached = cacheDir.get().asFile.resolve("${libraryName.get().removeSuffix(".so")}-${commit.get()}/${abi.get()}/${libraryName.get()}")
        if (!cached.isFile) {
            logger.lifecycle("Building ${libraryName.get()} at ${commit.get().take(7)}")
            val work = temporaryDir.apply { deleteRecursively(); mkdirs() }
            val project = projectDir.get().asFile
            exec.exec {
                commandLine(
                    listOf(
                        ndkDir.get().asFile.resolve("ndk-build").path,
                        "NDK_PROJECT_PATH=${project.path}",
                        "APP_BUILD_SCRIPT=${project.resolve("jni/Android.mk").path}",
                        "NDK_APPLICATION_MK=${project.resolve("jni/Application.mk").path}",
                        "NDK_OUT=${work.resolve("obj").path}",
                        "NDK_LIBS_OUT=${work.resolve("libs").path}",
                        "APP_ABI=${abi.get()}",
                        "APP_CPPFLAGS+=${flags.get().joinToString(" ")}",
                        "-j${Runtime.getRuntime().availableProcessors()}",
                    ) + variables.get(),
                )
            }
            cached.parentFile.mkdirs()
            work.resolve("libs/${abi.get()}/libretro.so").copyTo(cached, overwrite = true)
        }
        val out = outputDir.get().asFile.resolve(abi.get()).apply { deleteRecursively(); mkdirs() }
        cached.copyTo(out.resolve(libraryName.get()), overwrite = true)
    }
}

/**
 * bsnes-mercury (GPL-3.0), the Super Famicom core, built from libretro's repository at this commit,
 * balanced profile. See NOTICE. (Plain bsnes's libretro side gives no save RAM or memory map, which
 * the player's saves and the reader need.)
 */
val bsnesMercuryCommit = "79d7f9de218b6ffa65a80bbdc5828532bc239232"

val buildBsnes = tasks.register<BuildLibretroCore>("buildBsnes") {
    commit.set(bsnesMercuryCommit)
    submoduleDir.set(rootProject.layout.projectDirectory.dir("third_party/bsnes-mercury"))
    projectDir.set(rootProject.layout.projectDirectory.dir("third_party/bsnes-mercury/target-libretro"))
    libraryName.set("libbsnes_mercury_libretro_android.so")
    abi.set("arm64-v8a")
    variables.set(listOf("PROFILE=balanced"))
    flags.set(emptyList())
    ndkDir.set(androidComponents.sdkComponents.ndkDirectory)
    cacheDir.set(rootProject.layout.projectDirectory.dir(".gradle/cores"))
    outputDir.set(layout.buildDirectory.dir("generated/bsnes"))
}

val meikiDetect = "https://huggingface.co/rtr46/meiki.text.detect.v0/resolve/a9cffa4f60cbf72ddb87edf19c6f98a01cd042e6"
val meikiRecognize = "https://huggingface.co/rtr46/meiki.txt.recognition.v0/resolve/a28cf5874dc2438ebb1c86336be26bcec51e3375"

/** Meiki OCR's models (LGPL-3.0, trained on Japanese games), as assets. */
val downloadMeikiModels = tasks.register<DownloadPinnedFiles>("downloadMeikiModels") {
    subdir.set("meiki")
    files.set(
        listOf(
            "$meikiDetect/meiki.text.detect.v0.1.960x544.onnx 40b6a016667745cae7d3055929ae3b8b1e7716aac795f5904cd3c2c7c3b8404b detect.onnx",
            "$meikiRecognize/meiki.text.rec.v0.960x32.onnx 3e96bc772fbee9717e536a6353032bb944c3382dd2f6960ef4890decda43b000 rec_horizontal.onnx",
            "$meikiRecognize/meiki.text.rec.v0.vertical.32x480.onnx 2c2a83a23bc3b7e6c63962175f507ecc6c5e85cc174f17bdec37d9bbd0bf895a rec_vertical.onnx",
        ),
    )
    cacheDir.set(rootProject.layout.projectDirectory.dir(".gradle/meiki"))
    outputDir.set(layout.buildDirectory.dir("generated/meiki"))
}

/**
 * Libretro cores for the built-in player, as native libraries, from Lemuroid's prebuilt cores
 * (each under its own license: mGBA MPL-2.0, PCSX ReARMed GPL-2.0, PPSSPP GPL-2.0). See NOTICE.
 */
val lemuroidCores = "https://raw.githubusercontent.com/Swordfish90/LemuroidCores/fee2e824525daa22bcf318f96127fe43fa8a15ad"

val downloadCores = tasks.register<DownloadPinnedFiles>("downloadCores") {
    subdir.set("arm64-v8a")
    files.set(
        listOf(
            "$lemuroidCores/lemuroid_core_mgba/src/main/jniLibs/arm64-v8a/libmgba_libretro_android.so fbb03681b7d7ea4fa04bac0f60a005d9fcdc732ece3df1b715fabc19d1f9b2ab libmgba_libretro_android.so",
            "$lemuroidCores/lemuroid_core_pcsx_rearmed/src/main/jniLibs/arm64-v8a/libpcsx_rearmed_libretro_android.so 1c32c21dcc488889db7556bd0bd11c72219228f8d35d36bc30c640472af21fd3 libpcsx_rearmed_libretro_android.so",
            "$lemuroidCores/lemuroid_core_ppsspp/src/main/jniLibs/arm64-v8a/libppsspp_libretro_android.so 2d5370602e07df1e5bc0fb20467cff52aafa8ccbade904b5d7f60b3eebd2ff23 libppsspp_libretro_android.so",
            "$lemuroidCores/lemuroid_core_melonds/src/main/jniLibs/arm64-v8a/libmelonds_libretro_android.so 71f305fa273d27c57eb438fd3447a4c33bbd2f321ead9ac7d5c46d6a707d760d libmelonds_libretro_android.so",
        ),
    )
    cacheDir.set(rootProject.layout.projectDirectory.dir(".gradle/cores"))
    outputDir.set(layout.buildDirectory.dir("generated/cores"))
}

/** Files cores need beside them: PPSSPP's fonts, shaders and flash0, unpacked on first use. */
val downloadCoreAssets = tasks.register<DownloadPinnedFiles>("downloadCoreAssets") {
    subdir.set("cores")
    files.set(
        listOf(
            "$lemuroidCores/assets/ppsspp.zip 2ae4b85598b1759ee405cefeea2d8cf8a75971ff641b860b7fc45a8c83eca0d5 ppsspp.zip",
        ),
    )
    cacheDir.set(rootProject.layout.projectDirectory.dir(".gradle/cores"))
    outputDir.set(layout.buildDirectory.dir("generated/core-assets"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(downloadMeikiModels, DownloadPinnedFiles::outputDir)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(downloadCores, DownloadPinnedFiles::outputDir)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildBsnes, BuildLibretroCore::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(downloadCoreAssets, DownloadPinnedFiles::outputDir)
        // The PC hook kit (tools/hookbridge/kit.sh, which needs MinGW): packed when it's been
        // built; without it, Genkan just has no kit to install.
        val hookKit = rootProject.file("tools/hookbridge/build/assets")
        if (hookKit.isDirectory) variant.sources.assets?.addStaticSourceDirectory(hookKit.path)
    }
}
