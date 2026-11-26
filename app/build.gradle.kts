import org.gradle.process.ExecOperations
import javax.inject.Inject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.roborazzi)
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

/**
 * Beetle Saturn (GPL-2.0), Mednafen's Saturn core, built from libretro's repository at this commit.
 * Picked on the Thor over YabaSanshiro, the other candidate: full speed with half the CPU spare and
 * a right picture. See NOTICE.
 */
val beetleSaturnCommit = "7126d65b83b65b4316736434ecca2f5ff4f1a3dd"

val buildBeetleSaturn = tasks.register<BuildLibretroCore>("buildBeetleSaturn") {
    commit.set(beetleSaturnCommit)
    submoduleDir.set(rootProject.layout.projectDirectory.dir("third_party/beetle-saturn"))
    projectDir.set(rootProject.layout.projectDirectory.dir("third_party/beetle-saturn"))
    libraryName.set("libmednafen_saturn_libretro_android.so")
    abi.set("arm64-v8a")
    variables.set(emptyList())
    flags.set(emptyList())
    ndkDir.set(androidComponents.sdkComponents.ndkDirectory)
    cacheDir.set(rootProject.layout.projectDirectory.dir(".gradle/cores"))
    outputDir.set(layout.buildDirectory.dir("generated/beetle-saturn"))
}

/**
 * A libretro core built from source with CMake, from a submodule pinned at [commit] that a normal
 * checkout skips (`update = none` in .gitmodules: its externals are large). Built once per commit
 * into [cacheDir] with the zip of the [sysEntries] under the source's Data/Sys the core needs in its
 * system folder (none when the list is empty), so later builds (and CI, which caches the folder)
 * only copy both; the source and its externals are fetched, shallow, only when the cache has no
 * build of the commit. The zip is cached by its list of entries too: a changed list makes only a
 * new zip.
 */
abstract class BuildCmakeCore : DefaultTask() {
    @get:Input abstract val commit: Property<String>
    @get:Internal abstract val submoduleDir: DirectoryProperty
    @get:Input abstract val libraryName: Property<String>
    @get:Input abstract val abi: Property<String>
    @get:Input abstract val cmakeArgs: ListProperty<String>
    @get:Input abstract val target: Property<String>
    /** The source's own submodules the build doesn't need (Windows' Qt and FFmpeg). */
    @get:Input abstract val skipExternals: ListProperty<String>
    /** When given, the only submodules of the source the build needs (each with its own); else all but [skipExternals]. */
    @get:Input abstract val externals: ListProperty<String>
    @get:Input abstract val sysEntries: ListProperty<String>
    @get:Input abstract val sysZipName: Property<String>
    /** Genkan's own patches to the source (the .patch files in patches/<core>), applied in name order to a copy of it. */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val patches: ConfigurableFileCollection
    @get:Internal abstract val ndkDir: DirectoryProperty
    @get:Internal abstract val cmakeDir: DirectoryProperty
    @get:Internal abstract val cacheDir: DirectoryProperty
    @get:OutputDirectory abstract val libsDir: DirectoryProperty
    @get:OutputDirectory abstract val assetsDir: DirectoryProperty
    @get:Inject abstract val exec: ExecOperations

    init {
        skipExternals.convention(emptyList())
        externals.convention(emptyList())
        sysEntries.convention(emptyList())
        sysZipName.convention("")
    }

    private fun git(dir: File, vararg args: String): String = ByteArrayOutputStream().also { out ->
        exec.exec { commandLine(listOf("git", "-C", dir.path) + args); standardOutput = out }
    }.toString().trim()

    @TaskAction
    fun build() {
        val source = submoduleDir.get().asFile
        val root = source.parentFile.parentFile
        val path = source.relativeTo(root).path
        // The commit the repository pins (its gitlink), whether or not the source is checked out.
        val pinned = git(root, "ls-files", "-s", "--", path).split(Regex("\\s+")).getOrNull(1)
        check(pinned == commit.get()) { "$path is pinned at $pinned, not ${commit.get()}: update the commit here too" }
        val stem = libraryName.get().removeSuffix(".so")
        val patchFiles = patches.files.filter { it.isFile }.sortedBy { it.name }
        // A patched build is its own: the patches' hash is part of the cached library's name.
        val patchKey = if (patchFiles.isEmpty()) "" else "-p" + MessageDigest.getInstance("SHA-256")
            .digest(patchFiles.joinToString("") { it.name + "\n" + it.readText() }.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
        val cached = cacheDir.get().asFile.resolve("$stem-${commit.get()}$patchKey")
        val library = cached.resolve("${abi.get()}/${libraryName.get()}")
        val entriesKey = MessageDigest.getInstance("SHA-256").digest(sysEntries.get().joinToString("\n").toByteArray())
            .joinToString("") { "%02x".format(it) }.take(12)
        val sysZip = cached.resolve("${sysZipName.get().removeSuffix(".zip")}-$entriesKey.zip")
        if (!library.isFile) {
            fetch(root, source, path)
            logger.lifecycle("Building ${libraryName.get()} at ${commit.get().take(7)}")
            val temp = temporaryDir.apply { deleteRecursively(); mkdirs() }
            val work = temp.resolve("build").apply { mkdirs() }
            val buildSource = patched(source, temp, patchFiles)
            val cmakeBin = cmakeDir.get().asFile
            val ndk = ndkDir.get().asFile
            exec.exec {
                commandLine(
                    listOf(
                        cmakeBin.resolve("cmake").path, "-G", "Ninja", "-DCMAKE_MAKE_PROGRAM=${cmakeBin.resolve("ninja").path}",
                        "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_TOOLCHAIN_FILE=${ndk.resolve("build/cmake/android.toolchain.cmake").path}",
                        "-DANDROID_ABI=${abi.get()}", "-DANDROID_STL=c++_static",
                    ) + cmakeArgs.get() + listOf("-S", buildSource.path, "-B", work.path),
                )
            }
            exec.exec { commandLine(cmakeBin.resolve("cmake").path, "--build", work.path, "--target", target.get()) }
            val built = work.walkTopDown().first { it.name == "${target.get()}_android.so" || it.name == "${target.get()}.so" }
            library.parentFile.mkdirs()
            // The host's own prebuilt LLVM: there is one per NDK.
            val strip = ndk.resolve("toolchains/llvm/prebuilt").listFiles()!!.single().resolve("bin/llvm-strip")
            exec.exec { commandLine(strip.path, "-o", library.path, built.path) }
        }
        val assets = assetsDir.get().asFile.resolve("cores").apply { deleteRecursively(); mkdirs() }
        val libs = libsDir.get().asFile.resolve(abi.get()).apply { deleteRecursively(); mkdirs() }
        library.copyTo(libs.resolve(libraryName.get()), overwrite = true)
        // A core that needs nothing in its system folder but BIOS files has no zip.
        if (sysEntries.get().isEmpty()) return
        if (!sysZip.isFile) {
            fetch(root, source, path)
            cached.listFiles { f -> f.name.startsWith(sysZipName.get().removeSuffix(".zip") + "-") }?.forEach { it.delete() }
            zipSys(source.resolve("Data/Sys"), sysZip)
        }
        sysZip.copyTo(assets.resolve(sysZipName.get()), overwrite = true)
    }

    /**
     * The source to build: [source] itself without patches, else a copy of it in [temp] with each
     * patch applied. A patch that no longer applies fails the build: never built unpatched.
     */
    private fun patched(source: File, temp: File, patchFiles: List<File>): File {
        if (patchFiles.isEmpty()) return source
        val copy = temp.resolve("source")
        logger.lifecycle("Copying ${source.name} to apply ${patchFiles.size} patch(es)")
        exec.exec { commandLine("cp", "-R", source.path, copy.path) }
        // A submodule's .git names its git directory relative to itself: the copy needs it whole
        // (the build asks git for its version).
        val gitFile = source.resolve(".git")
        if (gitFile.isFile) {
            val gitDir = gitFile.readText().trim().removePrefix("gitdir:").trim()
            copy.resolve(".git").writeText("gitdir: ${source.resolve(gitDir).canonicalPath}\n")
        }
        for (patch in patchFiles) {
            // patch(1), not git apply: the copy sits inside Genkan's own repository. No fuzz: a
            // patch whose context moved doesn't apply, rather than landing near it.
            val applies = exec.exec {
                commandLine("patch", "-p1", "-F", "0", "--forward", "--dry-run", "-d", copy.path, "-i", patch.path)
                isIgnoreExitValue = true
            }
            check(applies.exitValue == 0) { "${patch.name} no longer applies to ${source.name} at ${commit.get().take(7)}: update or drop it (see its README)" }
            exec.exec { commandLine("patch", "-p1", "-F", "0", "--forward", "-d", copy.path, "-i", patch.path) }
            logger.lifecycle("Applied ${patch.name}")
        }
        return copy
    }

    /** The source at the pinned commit, with the externals the build uses, each one commit deep. */
    private fun fetch(root: File, source: File, path: String) {
        if (source.resolve(".git").exists() && git(source, "rev-parse", "HEAD") == commit.get()) return
        logger.lifecycle("Fetching $path at ${commit.get().take(7)}")
        git(root, "submodule", "update", "--init", "--checkout", "--depth", "1", "--", path)
        val wanted = externals.get().ifEmpty {
            // git config exits 1 when nothing matches: no .gitmodules, or an empty one (LRPS2's).
            val listed = ByteArrayOutputStream().also { out ->
                exec.exec {
                    commandLine("git", "-C", source.path, "config", "-f", ".gitmodules", "--get-regexp", "\\.path$")
                    standardOutput = out
                    isIgnoreExitValue = true
                }
            }.toString()
            listed.lines().map { it.substringAfter(' ') }.filter { it.isNotBlank() && it !in skipExternals.get() }
        }
        // A source with no submodules of its own has nothing more to fetch.
        if (wanted.isEmpty()) return
        git(source, *(listOf("submodule", "update", "--init", "--recursive", "--depth", "1", "--jobs", "8", "--") + wanted).toTypedArray())
    }

    /** [entries] of [sys] zipped under Sys/, in name order and with fixed times: the same zip from the same commit. */
    private fun zipSys(sys: File, zip: File) {
        val files = sysEntries.get().flatMap { entry -> sys.resolve(entry).walkTopDown().filter { it.isFile }.toList() }
            .sortedBy { it.relativeTo(sys).invariantSeparatorsPath }
        ZipOutputStream(zip.outputStream()).use { out ->
            for (file in files) {
                out.putNextEntry(ZipEntry("Sys/${file.relativeTo(sys).invariantSeparatorsPath}").apply { time = 0L })
                file.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
    }
}

/**
 * Dolphin (GPL-2.0-or-later), the GameCube core, built from libretro's repository at this commit
 * as libretro's buildbot builds it for Android, with the files of its Sys folder GameCube games
 * use (per-game settings, fonts, shaders). See NOTICE.
 */
val dolphinCommit = "e0f0bfec5003270c6252a51d1eae6e6bbec6c3ed"

val buildDolphin = tasks.register<BuildCmakeCore>("buildDolphin") {
    commit.set(dolphinCommit)
    submoduleDir.set(rootProject.layout.projectDirectory.dir("third_party/dolphin"))
    patches.from(rootProject.fileTree("patches/dolphin") { include("*.patch") })
    libraryName.set("libdolphin_libretro_android.so")
    abi.set("arm64-v8a")
    cmakeArgs.set(listOf("-DLIBRETRO=ON", "-DENABLE_LTO=ON", "-DANDROID_PLATFORM=android-21"))
    target.set("dolphin_libretro")
    skipExternals.set(listOf("Externals/Qt", "Externals/FFmpeg-bin", "Externals/wil"))
    // codehandler.bin runs Gecko codes; the core says the files are missing without it.
    sysEntries.set(listOf("GameSettings", "GC", "Shaders", "Resources", "ApprovedInis.json", "codehandler.bin"))
    sysZipName.set("dolphin.zip")
    ndkDir.set(androidComponents.sdkComponents.ndkDirectory)
    cmakeDir.set(androidComponents.sdkComponents.sdkDirectory.map { it.dir("cmake/3.31.6/bin") })
    cacheDir.set(rootProject.layout.projectDirectory.dir(".gradle/cores"))
    libsDir.set(layout.buildDirectory.dir("generated/dolphin/libs"))
    assetsDir.set(layout.buildDirectory.dir("generated/dolphin/assets"))
    // After the app's native configure, which installs the SDK's CMake when it's missing.
    dependsOn("configureCMakeDebug[arm64-v8a]")
}

/**
 * Flycast (GPL-2.0-or-later), the Dreamcast core, built from its repository at this commit as a
 * libretro core with OpenGL ES (the SH-4's ARM64 dynarec included), without Vulkan, Lua or the crash
 * reporter. Only the four submodules that build uses are fetched. See NOTICE.
 */
val flycastCommit = "12a7f18b81c0c7dc2e52c29b6615c7fe0d496ee5"

val buildFlycast = tasks.register<BuildCmakeCore>("buildFlycast") {
    commit.set(flycastCommit)
    submoduleDir.set(rootProject.layout.projectDirectory.dir("third_party/flycast"))
    libraryName.set("libflycast_libretro_android.so")
    abi.set("arm64-v8a")
    cmakeArgs.set(
        listOf(
            "-DLIBRETRO=ON", "-DUSE_GLES=ON", "-DUSE_VULKAN=OFF", "-DUSE_BREAKPAD=OFF", "-DUSE_LUA=OFF",
            "-DUSE_OPENMP=OFF", "-DANDROID_PLATFORM=android-33",
        ),
    )
    target.set("flycast_libretro")
    externals.set(listOf("core/deps/asio", "core/deps/libchdr", "core/deps/tinygettext", "core/deps/xbyak"))
    ndkDir.set(androidComponents.sdkComponents.ndkDirectory)
    cmakeDir.set(androidComponents.sdkComponents.sdkDirectory.map { it.dir("cmake/3.31.6/bin") })
    cacheDir.set(rootProject.layout.projectDirectory.dir(".gradle/cores"))
    libsDir.set(layout.buildDirectory.dir("generated/flycast/libs"))
    assetsDir.set(layout.buildDirectory.dir("generated/flycast/assets"))
    dependsOn("configureCMakeDebug[arm64-v8a]")
}

/**
 * LRPS2 (libretro/parallel-ps2, GPL-3.0), libretro's PCSX2 with native ARM64 recompilers for the EE
 * and IOP, built from its repository at this commit with the OpenGL ES renderer (libretrodroid gives
 * cores no Vulkan) and Genkan's patches. The PS2's own player unless ARMSX2 is chosen. See NOTICE.
 */
val lrps2Commit = "4d2c5d0904c339fd18252574e010fbf38d1b1929"

val buildLrps2 = tasks.register<BuildCmakeCore>("buildLrps2") {
    commit.set(lrps2Commit)
    submoduleDir.set(rootProject.layout.projectDirectory.dir("third_party/lrps2"))
    // Android can't load it unpatched (see patches/lrps2/README.md).
    patches.from(rootProject.fileTree("patches/lrps2") { include("*.patch") })
    libraryName.set("libpcsx2_libretro_android.so")
    abi.set("arm64-v8a")
    cmakeArgs.set(listOf("-DLIBRETRO=ON", "-DUSE_VULKAN=OFF", "-DANDROID_PLATFORM=android-33"))
    target.set("pcsx2_libretro")
    ndkDir.set(androidComponents.sdkComponents.ndkDirectory)
    cmakeDir.set(androidComponents.sdkComponents.sdkDirectory.map { it.dir("cmake/3.31.6/bin") })
    cacheDir.set(rootProject.layout.projectDirectory.dir(".gradle/cores"))
    libsDir.set(layout.buildDirectory.dir("generated/lrps2/libs"))
    assetsDir.set(layout.buildDirectory.dir("generated/lrps2/assets"))
    dependsOn("configureCMakeDebug[arm64-v8a]")
}

val meikiDetect ="https://huggingface.co/rtr46/meiki.text.detect.v0/resolve/a9cffa4f60cbf72ddb87edf19c6f98a01cd042e6"
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
 * (each under its own license: mGBA MPL-2.0, PCSX ReARMed GPL-2.0, PPSSPP GPL-2.0, Beetle PCE Fast
 * GPL-2.0). See NOTICE.
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
            "$lemuroidCores/lemuroid_core_mednafen_pce_fast/src/main/jniLibs/arm64-v8a/libmednafen_pce_fast_libretro_android.so 6487d7828b48d1f63d2233e45f5aa909f91543114355bf326364f430a37a32e9 libmednafen_pce_fast_libretro_android.so",
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

/**
 * A native host test: [testSources]' CMake project built for this machine with the SDK's CMake
 * (the version the app's native build uses), then its [target] run. The unit tests wait for it,
 * so CI and the local check run it.
 */
abstract class NativeHostTest : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val testSources: DirectoryProperty
    /** Further sources the test builds from (the code under test). */
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sources: ConfigurableFileCollection
    /** The commits of submodules it builds, so a new pin runs it again. */
    @get:Input abstract val commits: ListProperty<String>
    @get:Input abstract val target: Property<String>
    @get:Internal abstract val cmakeDir: DirectoryProperty
    @get:Internal abstract val buildDir: DirectoryProperty
    @get:OutputFile abstract val passed: RegularFileProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun run() {
        val bin = cmakeDir.get().asFile
        val cmake = File(bin, "cmake")
        check(cmake.canExecute()) { "No CMake at $cmake: the app's native build installs it" }
        val build = buildDir.get().asFile.path
        exec.exec {
            commandLine(
                cmake.path, "-S", testSources.get().asFile.path, "-B", build, "-G", "Ninja",
                "-DCMAKE_MAKE_PROGRAM=${File(bin, "ninja").path}", "-DCMAKE_BUILD_TYPE=Release",
            )
        }
        exec.exec { commandLine(cmake.path, "--build", build, "--target", target.get()) }
        exec.exec { commandLine(File(build, target.get()).path) }
        passed.get().asFile.writeText("passed\n")
    }
}

fun submoduleCommit(path: String) =
    providers.exec { commandLine("git", "-C", rootProject.file(path).path, "rev-parse", "HEAD") }.standardOutput.asText.map { it.trim() }

/** The native lookup (src/test/cpp): hoshidicts' frequency sorting. */
val nativeLookupTest = tasks.register<NativeHostTest>("nativeLookupTest") {
    testSources.set(layout.projectDirectory.dir("src/test/cpp"))
    sources.from(layout.projectDirectory.file("src/main/cpp/frequency_sorting.h"))
    commits.add(submoduleCommit("third_party/hoshidicts"))
    target.set("frequency_sorting_test")
    cmakeDir.set(androidComponents.sdkComponents.sdkDirectory.map { it.dir("cmake/3.31.6/bin") })
    buildDir.set(layout.buildDirectory.dir("native-tests"))
    passed.set(layout.buildDirectory.file("native-tests/passed"))
    // After the app's native configure, which installs the SDK's CMake when it's missing.
    dependsOn("configureCMakeDebug[arm64-v8a]")
}

/** The Yomikata script host (libretrodroid's scripthost.cpp, src/test/cpp there) with the runtime, on made-up memory. */
val scriptHostTest = tasks.register<NativeHostTest>("scriptHostTest") {
    testSources.set(rootProject.layout.projectDirectory.dir("libretrodroid/src/test/cpp"))
    sources.from(rootProject.files("libretrodroid/src/main/cpp/scripthost.h", "libretrodroid/src/main/cpp/scripthost.cpp"))
    commits.add(submoduleCommit("third_party/yomikata"))
    target.set("script_host_test")
    cmakeDir.set(androidComponents.sdkComponents.sdkDirectory.map { it.dir("cmake/3.31.6/bin") })
    buildDir.set(layout.buildDirectory.dir("script-host-test"))
    passed.set(layout.buildDirectory.file("script-host-test/passed"))
    dependsOn("configureCMakeDebug[arm64-v8a]")
}

/** The part of a hardware core's framebuffer its frame filled, which the player draws. */
val frameFillTest = tasks.register<NativeHostTest>("frameFillTest") {
    testSources.set(rootProject.layout.projectDirectory.dir("libretrodroid/src/test/cpp"))
    sources.from(rootProject.files("libretrodroid/src/main/cpp/renderers/framefill.h"))
    commits.add(submoduleCommit("third_party/yomikata"))
    target.set("frame_fill_test")
    cmakeDir.set(androidComponents.sdkComponents.sdkDirectory.map { it.dir("cmake/3.31.6/bin") })
    buildDir.set(layout.buildDirectory.dir("frame-fill-test"))
    passed.set(layout.buildDirectory.file("frame-fill-test/passed"))
    dependsOn("configureCMakeDebug[arm64-v8a]")
}

/** A console's address read through a core's memory descriptor, as libretro and Yomikata's harness define it. */
val memoryMapTest = tasks.register<NativeHostTest>("memoryMapTest") {
    testSources.set(rootProject.layout.projectDirectory.dir("libretrodroid/src/test/cpp"))
    sources.from(rootProject.files("libretrodroid/src/main/cpp/memorymap.h"))
    commits.add(submoduleCommit("third_party/yomikata"))
    target.set("memory_map_test")
    cmakeDir.set(androidComponents.sdkComponents.sdkDirectory.map { it.dir("cmake/3.31.6/bin") })
    buildDir.set(layout.buildDirectory.dir("memory-map-test"))
    passed.set(layout.buildDirectory.file("memory-map-test/passed"))
    dependsOn("configureCMakeDebug[arm64-v8a]")
}

/** When the frame loop holds a core back by how far its sound is ahead of the frames asked. */
val audioPaceTest = tasks.register<NativeHostTest>("audioPaceTest") {
    testSources.set(rootProject.layout.projectDirectory.dir("libretrodroid/src/test/cpp"))
    sources.from(rootProject.files("libretrodroid/src/main/cpp/audiopace.h"))
    commits.add(submoduleCommit("third_party/yomikata"))
    target.set("audio_pace_test")
    cmakeDir.set(androidComponents.sdkComponents.sdkDirectory.map { it.dir("cmake/3.31.6/bin") })
    buildDir.set(layout.buildDirectory.dir("audio-pace-test"))
    passed.set(layout.buildDirectory.file("audio-pace-test/passed"))
    dependsOn("configureCMakeDebug[arm64-v8a]")
}

/** One sector of a CHD's data track through libchdr: a disc's boot block, read for its id. */
val discSectorTest = tasks.register<NativeHostTest>("discSectorTest") {
    testSources.set(rootProject.layout.projectDirectory.dir("libretrodroid/src/test/cpp"))
    sources.from(rootProject.files("libretrodroid/src/main/cpp/discsector.h", "libretrodroid/src/main/cpp/discsector.cpp"))
    commits.add(submoduleCommit("third_party/yomikata"))
    commits.add(submoduleCommit("third_party/libchdr"))
    target.set("disc_sector_test")
    cmakeDir.set(androidComponents.sdkComponents.sdkDirectory.map { it.dir("cmake/3.31.6/bin") })
    buildDir.set(layout.buildDirectory.dir("disc-sector-test"))
    passed.set(layout.buildDirectory.file("disc-sector-test/passed"))
    dependsOn("configureCMakeDebug[arm64-v8a]")
}

/** Where a touch leaves a core's pointer: off the screen with the finger, or where it was (a Wii's). */
val pointerTest = tasks.register<NativeHostTest>("pointerTest") {
    testSources.set(rootProject.layout.projectDirectory.dir("libretrodroid/src/test/cpp"))
    sources.from(rootProject.files("libretrodroid/src/main/cpp/pointer.h"))
    commits.add(submoduleCommit("third_party/yomikata"))
    target.set("pointer_test")
    cmakeDir.set(androidComponents.sdkComponents.sdkDirectory.map { it.dir("cmake/3.31.6/bin") })
    buildDir.set(layout.buildDirectory.dir("pointer-test"))
    passed.set(layout.buildDirectory.file("pointer-test/passed"))
    dependsOn("configureCMakeDebug[arm64-v8a]")
}

/** The game a core names through Genkan's private call (a PS2 core's serial and CRC), as PINE reports it. */
val gameIdTest = tasks.register<NativeHostTest>("gameIdTest") {
    testSources.set(rootProject.layout.projectDirectory.dir("libretrodroid/src/test/cpp"))
    sources.from(rootProject.files("libretrodroid/src/main/cpp/gameid.h"))
    commits.add(submoduleCommit("third_party/yomikata"))
    target.set("game_id_test")
    cmakeDir.set(androidComponents.sdkComponents.sdkDirectory.map { it.dir("cmake/3.31.6/bin") })
    buildDir.set(layout.buildDirectory.dir("game-id-test"))
    passed.set(layout.buildDirectory.file("game-id-test/passed"))
    dependsOn("configureCMakeDebug[arm64-v8a]")
}

/**
 * The popup page's script tests (src/test/js), with Node's own test runner: no packages. The unit
 * tests wait for it, so CI and the local check run it.
 */
abstract class JsPopupTest : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val tests: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val popup: DirectoryProperty
    @get:OutputFile abstract val passed: RegularFileProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun run() {
        // The files themselves: Node before 26 reads a directory argument as a module to run.
        val files = tests.get().asFile.listFiles { f -> f.name.endsWith(".test.js") }.orEmpty().sortedBy { it.name }
        check(files.isNotEmpty()) { "No *.test.js in ${tests.get().asFile}" }
        exec.exec { commandLine(listOf("node", "--test") + files.map { it.path }) }
        passed.get().asFile.writeText("passed\n")
    }
}

val jsPopupTest = tasks.register<JsPopupTest>("jsPopupTest") {
    tests.set(layout.projectDirectory.dir("src/test/js"))
    popup.set(layout.projectDirectory.dir("src/main/assets/popup"))
    passed.set(layout.buildDirectory.file("js-tests/passed"))
}

tasks.matching { it.name == "testDebugUnitTest" }.configureEach { dependsOn(nativeLookupTest, scriptHostTest, frameFillTest, memoryMapTest, pointerTest, gameIdTest, audioPaceTest, discSectorTest, jsPopupTest) }

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(downloadMeikiModels, DownloadPinnedFiles::outputDir)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(downloadCores, DownloadPinnedFiles::outputDir)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildBsnes, BuildLibretroCore::outputDir)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildLrps2, BuildCmakeCore::libsDir)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildBeetleSaturn, BuildLibretroCore::outputDir)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildDolphin, BuildCmakeCore::libsDir)
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildFlycast, BuildCmakeCore::libsDir)
        variant.sources.assets?.addGeneratedSourceDirectory(buildDolphin, BuildCmakeCore::assetsDir)
        variant.sources.assets?.addGeneratedSourceDirectory(downloadCoreAssets, DownloadPinnedFiles::outputDir)
        // The PC hook kit (tools/hookbridge/kit.sh, which needs MinGW): packed when it's been
        // built; without it, Genkan just has no kit to install.
        val hookKit = rootProject.file("tools/hookbridge/build/assets")
        if (hookKit.isDirectory) variant.sources.assets?.addStaticSourceDirectory(hookKit.path)
    }
}
