// LibretroDroid (Filippo Scognamiglio, GPL-3.0) at 683652f8, with Genkan's additions: memory
// reads, an audio tap and frame grabs for the reader. See NOTICE.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.swordfish.libretrodroid"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 33
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
