/*
 *     Genkan's additions to LibretroDroid: JNI for the Yomikata script host (scripthost.h).
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 */

#include <jni.h>

#include <algorithm>
#include <map>
#include <mutex>
#include <string>
#include <vector>

#include "genkantap.h"
#include "scripthost.h"

static_assert(libretrodroid::ScriptHost::CARTRIDGE_IMAGE_AT == libretrodroid::GenkanTap::CARTRIDGE_IMAGE_AT,
              "the script host reads the cartridge's image where GenkanTap serves it");
#include "yomikata_reader.h"

using libretrodroid::GenkanTap;
using libretrodroid::ScriptHost;

namespace {

std::mutex scriptLock;
ScriptHost host;
/** The game's memory as it is this frame: read once, so the script's many reads don't each take the lock. */
std::vector<GenkanTap::Region> regions;

std::string bytesOf(JNIEnv *env, jbyteArray array) {
    if (array == nullptr) return {};
    jsize length = env->GetArrayLength(array);
    std::string out(static_cast<size_t>(length), '\0');
    env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

std::string stringOf(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

/** UTF-8 as bytes: Java's own strings from JNI are modified UTF-8, which a character past U+FFFF isn't. */
jbyteArray utf8(JNIEnv *env, const std::string &text) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    return out;
}

size_t readMemory(uint64_t address, uint8_t *out, size_t size) {
    return GenkanTap::readClipped(regions, address, out, size);
}

int64_t regionSize(const std::string &name) {
    if (name != "vram") return -1;
    size_t size = GenkanTap::getInstance().videoRamSize();
    return size > 0 ? static_cast<int64_t>(size) : -1;
}

size_t regionRead(const std::string &name, uint64_t offset, uint8_t *out, size_t size) {
    if (name != "vram") return 0;
    size_t total = GenkanTap::getInstance().videoRamSize();
    if (offset >= total) return 0;
    size_t n = std::min<size_t>(size, total - offset);
    return GenkanTap::getInstance().readVideoRam(offset, out, n) ? n : 0;
}

}

extern "C" {

/** Loads a script; null when it loaded, else why not (UTF-8). On the emulation thread. */
JNIEXPORT jbyteArray JNICALL Java_com_swordfish_libretrodroid_GenkanTap_scriptLoad(
    JNIEnv *env, jclass, jstring name, jbyteArray source, jobjectArray dataNames, jobjectArray dataFiles,
    jstring platform, jstring gameId, jstring buildId, jlong pointerMask, jlong ramBase
) {
    std::lock_guard<std::mutex> lock(scriptLock);
    std::map<std::string, std::string> data;
    jsize count = dataNames != nullptr ? env->GetArrayLength(dataNames) : 0;
    for (jsize i = 0; i < count; i++) {
        auto key = static_cast<jstring>(env->GetObjectArrayElement(dataNames, i));
        auto file = static_cast<jbyteArray>(env->GetObjectArrayElement(dataFiles, i));
        data[stringOf(env, key)] = bytesOf(env, file);
        env->DeleteLocalRef(key);
        env->DeleteLocalRef(file);
    }
    ScriptHost::Game game;
    game.platform = stringOf(env, platform);
    game.gameId = stringOf(env, gameId);
    game.buildId = stringOf(env, buildId);
    game.pointerMask = static_cast<uint64_t>(pointerMask);
    game.ramBase = static_cast<uint64_t>(ramBase);
    game.cartridgeImage = GenkanTap::getInstance().hasCartridgeImage();
    // The script checks the game's memory as it loads.
    regions = GenkanTap::getInstance().regions();
    std::string error = host.load(stringOf(env, name), bytesOf(env, source), game, std::move(data), readMemory, regionSize, regionRead);
    return error.empty() ? nullptr : utf8(env, error);
}

JNIEXPORT jint JNICALL Java_com_swordfish_libretrodroid_GenkanTap_scriptLevel(JNIEnv *, jclass) {
    return yk_script_level();
}

JNIEXPORT void JNICALL Java_com_swordfish_libretrodroid_GenkanTap_scriptUnload(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(scriptLock);
    host.unload();
}

JNIEXPORT jboolean JNICALL Java_com_swordfish_libretrodroid_GenkanTap_scriptLoaded(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(scriptLock);
    return host.loaded() ? JNI_TRUE : JNI_FALSE;
}

/** Runs the script for frame `frame`; its events, each "kind<TAB>json" in UTF-8, or null when there are none. */
JNIEXPORT jobjectArray JNICALL Java_com_swordfish_libretrodroid_GenkanTap_scriptFrame(JNIEnv *env, jclass, jlong frame) {
    std::vector<ScriptHost::Event> events;
    {
        std::lock_guard<std::mutex> lock(scriptLock);
        if (!host.loaded()) return nullptr;
        regions = GenkanTap::getInstance().regions();
        events = host.frame(static_cast<uint64_t>(frame));
    }
    if (events.empty()) return nullptr;
    jclass bytesClass = env->FindClass("[B");
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(events.size()), bytesClass, nullptr);
    for (size_t i = 0; i < events.size(); i++) {
        jbyteArray value = utf8(env, std::to_string(events[i].first) + "\t" + events[i].second);
        env->SetObjectArrayElement(out, static_cast<jsize>(i), value);
        env->DeleteLocalRef(value);
    }
    return out;
}

JNIEXPORT void JNICALL Java_com_swordfish_libretrodroid_GenkanTap_scriptStateLoaded(JNIEnv *, jclass) {
    std::lock_guard<std::mutex> lock(scriptLock);
    host.stateLoaded();
}

JNIEXPORT void JNICALL Java_com_swordfish_libretrodroid_GenkanTap_scriptPicture(JNIEnv *, jclass, jint width, jint height) {
    std::lock_guard<std::mutex> lock(scriptLock);
    host.picture(static_cast<uint32_t>(width), static_cast<uint32_t>(height));
}

}
