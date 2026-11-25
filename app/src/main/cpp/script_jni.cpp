// The Yomikata script host (libretrodroid's scripthost.cpp, the same the built-in player runs) in
// Genkan's own process, for a game in another emulator: its memory is read through Kotlin (a
// MemorySource, ARMSX2's PINE), called back on the thread that runs the script.

#include <jni.h>

#include <algorithm>
#include <map>
#include <mutex>
#include <string>
#include <vector>

#include "scripthost.h"
#include "yomikata_reader.h"

using libretrodroid::ScriptHost;

namespace {

std::mutex scriptLock;
ScriptHost host;

/** The MemorySource the running call reads through, and its JNIEnv: set only while a call runs. */
JNIEnv *callEnv = nullptr;
jobject callMemory = nullptr;
jmethodID readMethod = nullptr;

class Reading {
public:
    Reading(JNIEnv *env, jobject memory) {
        callEnv = env;
        callMemory = memory;
        jclass type = env->GetObjectClass(memory);
        readMethod = env->GetMethodID(type, "read", "(JI)[B");
        env->DeleteLocalRef(type);
    }
    ~Reading() {
        callEnv = nullptr;
        callMemory = nullptr;
    }
};

size_t readMemory(uint64_t address, uint8_t *out, size_t size) {
    if (callEnv == nullptr || callMemory == nullptr || readMethod == nullptr) return 0;
    auto bytes = static_cast<jbyteArray>(callEnv->CallObjectMethod(
        callMemory, readMethod, static_cast<jlong>(address), static_cast<jint>(size)));
    if (callEnv->ExceptionCheck()) {
        callEnv->ExceptionClear();
        return 0;
    }
    if (bytes == nullptr) return 0;
    jsize count = std::min<jsize>(callEnv->GetArrayLength(bytes), static_cast<jsize>(size));
    callEnv->GetByteArrayRegion(bytes, 0, count, reinterpret_cast<jbyte *>(out));
    callEnv->DeleteLocalRef(bytes);
    return static_cast<size_t>(count);
}

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

}

extern "C" {

/** Loads a script; null when it loaded, else why not (UTF-8). It checks the game's memory as it loads. */
JNIEXPORT jbyteArray JNICALL Java_app_genkan_script_AppScriptHost_loadNative(
    JNIEnv *env, jobject, jstring name, jbyteArray source, jobjectArray dataNames, jobjectArray dataFiles,
    jstring platform, jstring gameId, jstring buildId, jlong pointerMask, jlong ramBase, jobject memory
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
    Reading reading(env, memory);
    // No named regions: an external emulator's memory comes as main RAM only.
    std::string error = host.load(stringOf(env, name), bytesOf(env, source), game, std::move(data), readMemory, nullptr, nullptr);
    return error.empty() ? nullptr : utf8(env, error);
}

/** Runs the script for frame `frame`, reading through `memory`; its events, each "kind<TAB>json" in UTF-8, or null. */
JNIEXPORT jobjectArray JNICALL Java_app_genkan_script_AppScriptHost_frameNative(JNIEnv *env, jobject, jlong frame, jobject memory) {
    std::vector<ScriptHost::Event> events;
    {
        std::lock_guard<std::mutex> lock(scriptLock);
        if (!host.loaded()) return nullptr;
        Reading reading(env, memory);
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

JNIEXPORT jint JNICALL Java_app_genkan_script_AppScriptHost_level(JNIEnv *, jobject) {
    return yk_script_level();
}

JNIEXPORT void JNICALL Java_app_genkan_script_AppScriptHost_unload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(scriptLock);
    host.unload();
}

JNIEXPORT void JNICALL Java_app_genkan_script_AppScriptHost_stateLoaded(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(scriptLock);
    host.stateLoaded();
}

}
