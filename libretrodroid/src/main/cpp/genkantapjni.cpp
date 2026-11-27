/*
 *     Genkan's additions to LibretroDroid: JNI for GenkanTap.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 */

#include <jni.h>

#include <vector>

#include "genkantap.h"
#include "discsector.h"
#include "framestats.h"
#include "input.h"
#include "libretrodroid.h"

using libretrodroid::FrameStats;
using libretrodroid::GenkanTap;
using libretrodroid::Input;
using libretrodroid::LibretroDroid;

extern "C" {

JNIEXPORT jbyteArray JNICALL Java_com_swordfish_libretrodroid_GenkanTap_chdDataSector(JNIEnv *env, jclass, jstring path, jint sector) {
    if (path == nullptr || sector < 0) return nullptr;
    const char *chars = env->GetStringUTFChars(path, nullptr);
    std::vector<uint8_t> data = libretrodroid::chdDataSector(chars, static_cast<uint32_t>(sector));
    env->ReleaseStringUTFChars(path, chars);
    if (data.empty()) return nullptr;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(data.size()));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(data.size()), reinterpret_cast<const jbyte *>(data.data()));
    return result;
}

JNIEXPORT jlong JNICALL Java_com_swordfish_libretrodroid_GenkanTap_lastStepNanos(JNIEnv *, jclass) {
    return FrameStats::getInstance().lastStepNanos();
}

JNIEXPORT jlongArray JNICALL Java_com_swordfish_libretrodroid_GenkanTap_frameStats(JNIEnv *env, jclass) {
    auto values = FrameStats::getInstance().take();
    jlongArray result = env->NewLongArray(static_cast<jsize>(values.size()));
    env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), reinterpret_cast<const jlong *>(values.data()));
    return result;
}

JNIEXPORT jlongArray JNICALL Java_com_swordfish_libretrodroid_GenkanTap_memoryRegions(JNIEnv *env, jclass) {
    auto regions = GenkanTap::getInstance().regions();
    jlongArray result = env->NewLongArray(static_cast<jsize>(regions.size() * 3));
    std::vector<jlong> values;
    for (const auto &r : regions) {
        values.push_back(static_cast<jlong>(r.start));
        values.push_back(static_cast<jlong>(r.length));
        values.push_back(r.mirror ? 1 : 0);
    }
    env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    return result;
}

JNIEXPORT jboolean JNICALL Java_com_swordfish_libretrodroid_GenkanTap_readMemory(
    JNIEnv *env, jclass, jlong address, jbyteArray out, jint size
) {
    if (size <= 0 || size > env->GetArrayLength(out)) return JNI_FALSE;
    auto *bytes = env->GetByteArrayElements(out, nullptr);
    bool ok = GenkanTap::getInstance().read(static_cast<uint64_t>(address), reinterpret_cast<uint8_t *>(bytes), size);
    env->ReleaseByteArrayElements(out, bytes, ok ? 0 : JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloatArray JNICALL Java_com_swordfish_libretrodroid_GenkanTap_pictureBounds(JNIEnv *env, jclass) {
    float bounds[4];
    if (!LibretroDroid::getInstance().pictureBounds(bounds)) return nullptr;
    jfloatArray out = env->NewFloatArray(4);
    env->SetFloatArrayRegion(out, 0, 4, bounds);
    return out;
}

JNIEXPORT jlong JNICALL Java_com_swordfish_libretrodroid_GenkanTap_videoRamSize(JNIEnv *, jclass) {
    return static_cast<jlong>(GenkanTap::getInstance().videoRamSize());
}

JNIEXPORT jboolean JNICALL Java_com_swordfish_libretrodroid_GenkanTap_readVideoRam(
    JNIEnv *env, jclass, jlong offset, jbyteArray out, jint size
) {
    if (size <= 0 || size > env->GetArrayLength(out)) return JNI_FALSE;
    auto *bytes = env->GetByteArrayElements(out, nullptr);
    bool ok = GenkanTap::getInstance().readVideoRam(static_cast<uint64_t>(offset), reinterpret_cast<uint8_t *>(bytes), size);
    env->ReleaseByteArrayElements(out, bytes, ok ? 0 : JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_swordfish_libretrodroid_GenkanTap_requestFrame(JNIEnv *, jclass) {
    GenkanTap::getInstance().requestFrame();
}

JNIEXPORT jint JNICALL Java_com_swordfish_libretrodroid_GenkanTap_audioSampleRate(JNIEnv *, jclass) {
    return GenkanTap::getInstance().getSampleRate();
}

JNIEXPORT jint JNICALL Java_com_swordfish_libretrodroid_GenkanTap_drainAudio(JNIEnv *env, jclass, jshortArray out) {
    auto *samples = env->GetShortArrayElements(out, nullptr);
    size_t n = GenkanTap::getInstance().drainAudio(samples, env->GetArrayLength(out));
    env->ReleaseShortArrayElements(out, samples, 0);
    return static_cast<jint>(n);
}

JNIEXPORT void JNICALL Java_com_swordfish_libretrodroid_GenkanTap_clearAudioProblem(JNIEnv *, jclass) {
    GenkanTap::getInstance().setAudioProblem("");
}

JNIEXPORT jstring JNICALL Java_com_swordfish_libretrodroid_GenkanTap_audioProblem(JNIEnv *env, jclass) {
    std::string problem = GenkanTap::getInstance().audioProblem();
    return problem.empty() ? nullptr : env->NewStringUTF(problem.c_str());
}

JNIEXPORT void JNICALL Java_com_swordfish_libretrodroid_GenkanTap_setSplitScreens(JNIEnv *, jclass, jboolean split) {
    GenkanTap::getInstance().setSplitScreens(split == JNI_TRUE);
}

JNIEXPORT jstring JNICALL Java_com_swordfish_libretrodroid_GenkanTap_gameId(JNIEnv *env, jclass) {
    std::string id = GenkanTap::getInstance().gameId();
    return id.empty() ? nullptr : env->NewStringUTF(id.c_str());
}

JNIEXPORT void JNICALL Java_com_swordfish_libretrodroid_GenkanTap_setKeepPointer(JNIEnv *, jclass, jboolean keep) {
    GenkanTap::getInstance().setKeepPointer(keep == JNI_TRUE);
}

JNIEXPORT void JNICALL Java_com_swordfish_libretrodroid_GenkanTap_setCartridgeImage(JNIEnv *env, jclass, jbyteArray image) {
    std::vector<uint8_t> out;
    if (image != nullptr) {
        out.resize(static_cast<size_t>(env->GetArrayLength(image)));
        env->GetByteArrayRegion(image, 0, static_cast<jsize>(out.size()), reinterpret_cast<jbyte *>(out.data()));
    }
    GenkanTap::getInstance().setCartridgeImage(std::move(out));
}

/** `stretches` as (console address, offset in the system RAM, length) triples. */
JNIEXPORT void JNICALL Java_com_swordfish_libretrodroid_GenkanTap_setSystemRamStretches(
    JNIEnv *env, jclass, jlongArray stretches, jint wordSize
) {
    std::vector<GenkanTap::Stretch> out;
    jsize count = stretches != nullptr ? env->GetArrayLength(stretches) : 0;
    std::vector<jlong> values(static_cast<size_t>(count));
    if (count > 0) env->GetLongArrayRegion(stretches, 0, count, values.data());
    for (jsize i = 0; i + 2 < count; i += 3) {
        out.push_back(GenkanTap::Stretch {
            static_cast<uint64_t>(values[i]), static_cast<uint64_t>(values[i + 1]), static_cast<uint64_t>(values[i + 2]),
        });
    }
    GenkanTap::getInstance().setSystemRamStretches(std::move(out), wordSize == 2 || wordSize == 4 ? static_cast<unsigned>(wordSize) : 0);
}

JNIEXPORT void JNICALL Java_com_swordfish_libretrodroid_GenkanTap_setReadsWhole(JNIEnv *, jclass, jboolean whole) {
    GenkanTap::getInstance().setReadsWhole(whole == JNI_TRUE);
}

JNIEXPORT jlong JNICALL Java_com_swordfish_libretrodroid_GenkanTap_packedFrameSize(JNIEnv *, jclass) {
    auto [width, height] = GenkanTap::getInstance().frameSize();
    return (static_cast<jlong>(width) << 32) | height;
}

JNIEXPORT jboolean JNICALL Java_com_swordfish_libretrodroid_GenkanTap_copyFrame(
    JNIEnv *env, jclass, jobject buffer, jint firstRow, jint rows
) {
    auto *out = static_cast<uint8_t *>(env->GetDirectBufferAddress(buffer));
    if (out == nullptr || firstRow < 0 || rows <= 0) return JNI_FALSE;
    size_t capacity = static_cast<size_t>(env->GetDirectBufferCapacity(buffer));
    return GenkanTap::getInstance().copyFrame(out, capacity, firstRow, rows) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_swordfish_libretrodroid_GenkanTap_touchLowerScreen(JNIEnv *, jclass, jfloat x, jfloat y) {
    // The pointer spans the whole stacked frame: the lower screen is its bottom half.
    bool inside = x >= 0 && y >= 0;
    LibretroDroid::getInstance().onMotionEvent(
        0, Input::MOTION_SOURCE_POINTER, inside ? x : -1.0f, inside ? 0.5f + y / 2.0f : -1.0f
    );
}

}
