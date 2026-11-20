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

using libretrodroid::GenkanTap;

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

}
