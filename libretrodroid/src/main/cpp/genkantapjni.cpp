/*
 *     Genkan's additions to LibretroDroid: JNI for GenkanTap.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 */

#include <jni.h>

#include "genkantap.h"
#include "input.h"
#include "libretrodroid.h"

using libretrodroid::GenkanTap;
using libretrodroid::Input;
using libretrodroid::LibretroDroid;

extern "C" {

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

JNIEXPORT jint JNICALL Java_com_swordfish_libretrodroid_GenkanTap_audioSampleRate(JNIEnv *, jclass) {
    return GenkanTap::getInstance().getSampleRate();
}

JNIEXPORT jint JNICALL Java_com_swordfish_libretrodroid_GenkanTap_drainAudio(JNIEnv *env, jclass, jshortArray out) {
    auto *samples = env->GetShortArrayElements(out, nullptr);
    size_t n = GenkanTap::getInstance().drainAudio(samples, env->GetArrayLength(out));
    env->ReleaseShortArrayElements(out, samples, 0);
    return static_cast<jint>(n);
}

JNIEXPORT void JNICALL Java_com_swordfish_libretrodroid_GenkanTap_setSplitScreens(JNIEnv *, jclass, jboolean split) {
    GenkanTap::getInstance().setSplitScreens(split == JNI_TRUE);
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
