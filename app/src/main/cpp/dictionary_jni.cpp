// SPDX-License-Identifier: GPL-3.0-only
//
// JNI over hoshidicts' C API (include/hoshidicts_c.h). Text crosses as UTF-8 byte arrays, not
// jstring: JNI's string calls use modified UTF-8, which mangles characters outside the BMP
// (𠮟, the flag emoji in JMdict's examples). Results go back as one JSON document per call, so
// Kotlin reads the fields it needs and the popup page gets the rest untouched.

#include <jni.h>

#include <algorithm>
#include <cstdio>
#include <string>
#include <string_view>
#include <vector>

#include "hoshidicts_c.h"
#include "frequency_sorting.h"

namespace {

struct Session {
    hd_query* query;
    hd_deinflector* deinflector;
    hd_lookup* lookup;
};

std::string Utf8(JNIEnv* env, jbyteArray bytes) {
    if (bytes == nullptr) return {};
    const jsize size = env->GetArrayLength(bytes);
    std::string out(static_cast<size_t>(size), '\0');
    env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(out.data()));
    return out;
}

jbyteArray Bytes(JNIEnv* env, const std::string& text) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(text.size()));
    if (out != nullptr) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte*>(text.data()));
    }
    return out;
}

std::vector<std::string> Paths(JNIEnv* env, jobjectArray array) {
    std::vector<std::string> out;
    if (array == nullptr) return out;
    const jsize count = env->GetArrayLength(array);
    for (jsize i = 0; i < count; ++i) {
        auto item = static_cast<jbyteArray>(env->GetObjectArrayElement(array, i));
        out.push_back(Utf8(env, item));
        env->DeleteLocalRef(item);
    }
    return out;
}

/// A JSON writer just big enough for these results.
class Json {
public:
    Json& Raw(std::string_view text) {
        out.append(text);
        return *this;
    }
    Json& Str(std::string_view text) {
        out.push_back('"');
        for (const char c : text) {
            switch (c) {
            case '"': out.append("\\\""); break;
            case '\\': out.append("\\\\"); break;
            case '\n': out.append("\\n"); break;
            case '\r': out.append("\\r"); break;
            case '\t': out.append("\\t"); break;
            default:
                if (static_cast<unsigned char>(c) < 0x20) {
                    char escaped[8];
                    std::snprintf(escaped, sizeof escaped, "\\u%04x", c);
                    out.append(escaped);
                } else {
                    out.push_back(c);
                }
            }
        }
        out.push_back('"');
        return *this;
    }
    Json& Str(hd_str text) {
        return Str(std::string_view(text.ptr == nullptr ? "" : text.ptr, text.len));
    }
    Json& Key(std::string_view key) {
        Comma();
        Str(key);
        out.push_back(':');
        return *this;
    }
    Json& Open(char bracket) {
        Comma();
        out.push_back(bracket);
        return *this;
    }
    Json& Close(char bracket) {
        out.push_back(bracket);
        return *this;
    }
    /// Starts a value in an array, or after a key.
    Json& Item() {
        Comma();
        return *this;
    }
    Json& Num(double value) {
        Item();
        char text[32];
        std::snprintf(text, sizeof text, "%.17g", value);
        out.append(text);
        return *this;
    }
    Json& Int(long long value) {
        Item();
        out.append(std::to_string(value));
        return *this;
    }
    Json& Field(std::string_view key, hd_str value) {
        Key(key);
        return Str(value);
    }
    Json& Field(std::string_view key, std::string_view value) {
        Key(key);
        return Str(value);
    }
    std::string Take() {
        return std::move(out);
    }

private:
    /// A comma before anything that is not the first in its object or array.
    void Comma() {
        if (!out.empty() && out.back() != '[' && out.back() != '{' && out.back() != ':') {
            out.push_back(',');
        }
    }

    std::string out;
};

void WriteTerm(Json& json, const hd_term_result& term) {
    json.Field("expression", term.expression).Field("reading", term.reading).Field("rules", term.rules);
    json.Key("score").Num(term.score);
    json.Key("glossaries").Open('[');
    for (size_t i = 0; i < term.glossaries_count; ++i) {
        const hd_glossary_entry& g = term.glossaries[i];
        // The glossary is the dictionary's own JSON; the popup parses it.
        json.Open('{')
            .Field("dictionary", g.dict_name)
            .Field("glossary", g.glossary)
            .Field("definitionTags", g.definition_tags)
            .Field("termTags", g.term_tags)
            .Close('}');
    }
    json.Close(']');
    json.Key("frequencies").Open('[');
    for (size_t i = 0; i < term.frequencies_count; ++i) {
        const hd_frequency_entry& f = term.frequencies[i];
        json.Open('{').Field("dictionary", f.dict_name).Key("values").Open('[');
        for (size_t j = 0; j < f.frequencies_count; ++j) {
            json.Open('{');
            json.Key("value").Int(f.frequencies[j].value);
            json.Field("display", f.frequencies[j].display_value).Close('}');
        }
        json.Close(']').Close('}');
    }
    json.Close(']');
    json.Key("pitches").Open('[');
    for (size_t i = 0; i < term.pitches_count; ++i) {
        const hd_pitch_entry& p = term.pitches[i];
        json.Open('{').Field("dictionary", p.dict_name).Key("pitches").Open('[');
        for (size_t j = 0; j < p.pitches_count; ++j) {
            const hd_pitch& pitch = p.pitches[j];
            json.Open('{');
            json.Key("position").Int(pitch.position);
            json.Field("pattern", pitch.pattern);
            json.Key("nasal").Open('[');
            for (size_t k = 0; k < pitch.nasal_count; ++k) json.Int(pitch.nasal[k]);
            json.Close(']').Key("devoice").Open('[');
            for (size_t k = 0; k < pitch.devoice_count; ++k) json.Int(pitch.devoice[k]);
            json.Close(']').Close('}');
        }
        json.Close(']').Key("transcriptions").Open('[');
        for (size_t j = 0; j < p.transcriptions_count; ++j) json.Item().Str(p.transcriptions[j]);
        json.Close(']').Close('}');
    }
    json.Close(']');
}

Session* Of(jlong handle) {
    return reinterpret_cast<Session*>(handle);
}

/// One code point of UTF-8 text, with where it starts in the text's UTF-16 (Kotlin's indices).
struct CodePoint {
    char32_t value;
    size_t byte;
    size_t utf16;
};

std::vector<CodePoint> Decode(const std::string& text) {
    std::vector<CodePoint> out;
    size_t i = 0, utf16 = 0;
    while (i < text.size()) {
        const auto b = static_cast<unsigned char>(text[i]);
        const size_t n = b < 0x80 ? 1 : b < 0xE0 ? 2 : b < 0xF0 ? 3 : 4;
        char32_t c = n == 1 ? b : b & (0xFF >> (n + 1));
        for (size_t k = 1; k < n && i + k < text.size(); ++k) c = (c << 6) | (static_cast<unsigned char>(text[i + k]) & 0x3F);
        out.push_back({c, i, utf16});
        utf16 += c > 0xFFFF ? 2 : 1;
        i += n;
    }
    return out;
}

bool IsKanji(char32_t c) {
    return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF) || (c >= 0xF900 && c <= 0xFAFF) ||
           (c >= 0x20000 && c <= 0x2FA1F) || c == 0x3005;
}
bool IsHiragana(char32_t c) { return c >= 0x3041 && c <= 0x309F; }
bool IsKatakana(char32_t c) { return (c >= 0x30A1 && c <= 0x30FF) || (c >= 0xFF66 && c <= 0xFF9F); }

/// How many code points UTF-8 [text] holds.
size_t CodePoints(hd_str text) {
    size_t n = 0;
    for (size_t i = 0; i < text.len; ++i) {
        if ((static_cast<unsigned char>(text.ptr[i]) & 0xC0) != 0x80) ++n;
    }
    return n;
}

} // namespace

extern "C" {

JNIEXPORT jbyteArray JNICALL Java_app_genkan_dictionary_NativeDictionaries_importZip(
    JNIEnv* env, jobject, jbyteArray zip, jbyteArray outputDir, jboolean lowRam) {
    const std::string source = Utf8(env, zip), output = Utf8(env, outputDir);
    hd_import_result* r = hd_import(source.c_str(), output.c_str(), lowRam ? 1 : 0);
    Json json;
    json.Open('{');
    if (r == nullptr) {
        json.Key("success").Raw("false").Field("error", "import returned nothing");
    } else {
        json.Key("success").Raw(hd_import_result_success(r) ? "true" : "false");
        const char* title = hd_import_result_title(r);
        const char* error = hd_import_result_error(r);
        json.Field("title", title == nullptr ? "" : title).Field("error", error == nullptr ? "" : error);
        json.Key("terms").Int(static_cast<long long>(hd_import_result_term_count(r)));
        json.Key("meta").Int(static_cast<long long>(hd_import_result_meta_count(r)));
        json.Key("frequencies").Int(static_cast<long long>(hd_import_result_freq_count(r)));
        json.Key("pitches").Int(static_cast<long long>(hd_import_result_pitch_count(r)));
        json.Key("kanji").Int(static_cast<long long>(hd_import_result_kanji_count(r)));
        json.Key("media").Int(static_cast<long long>(hd_import_result_media_count(r)));
        hd_import_result_free(r);
    }
    json.Close('}');
    return Bytes(env, json.Take());
}

/// A lookup over the given dictionaries, in order; 0 when one of them would not open.
JNIEXPORT jlong JNICALL Java_app_genkan_dictionary_NativeDictionaries_open(
    JNIEnv* env, jobject, jobjectArray terms, jobjectArray frequencies, jobjectArray pitches, jobjectArray kanji) {
    hd_query* q = hd_query_new();
    bool ok = true;
    for (const auto& path : Paths(env, terms)) ok = ok && hd_query_add_term_dict(q, path.c_str()) == 0;
    for (const auto& path : Paths(env, frequencies)) ok = ok && hd_query_add_freq_dict(q, path.c_str()) == 0;
    for (const auto& path : Paths(env, pitches)) ok = ok && hd_query_add_pitch_dict(q, path.c_str()) == 0;
    for (const auto& path : Paths(env, kanji)) ok = ok && hd_query_add_kanji_dict(q, path.c_str()) == 0;
    if (!ok) {
        hd_query_free(q);
        return 0;
    }
    hd_deinflector* d = hd_deinflector_new();
    auto* session = new Session{q, d, hd_lookup_new(q, d)};
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL Java_app_genkan_dictionary_NativeDictionaries_close(JNIEnv*, jobject, jlong handle) {
    Session* s = Of(handle);
    if (s == nullptr) return;
    hd_lookup_free(s->lookup);
    hd_deinflector_free(s->deinflector);
    hd_query_free(s->query);
    delete s;
}

/// How many code points a lookup should be handed so the longest key can match.
JNIEXPORT jint JNICALL Java_app_genkan_dictionary_NativeDictionaries_scanLength(JNIEnv*, jobject, jlong handle) {
    const size_t longest = hd_query_max_long_key_length(Of(handle)->query);
    return static_cast<jint>(longest > 16 ? longest + 8 : 24);
}

JNIEXPORT jbyteArray JNICALL Java_app_genkan_dictionary_NativeDictionaries_lookup(
    JNIEnv* env, jobject, jlong handle, jbyteArray text, jint maxResults, jint scanLength, jbyteArray frequencyDictionary,
    jint frequencyOrder) {
    const std::string input = Utf8(env, text), freq = Utf8(env, frequencyDictionary);
    const hd_lookup_options options = Sorting(freq, frequencyOrder);
    const hd_lookup_result* results = nullptr;
    size_t count = 0;
    hd_lookup_results* r = hd_lookup_run_with_options(Of(handle)->lookup, input.c_str(), maxResults,
                                                      static_cast<size_t>(scanLength), &options, &results, &count);
    Json json;
    json.Open('[');
    for (size_t i = 0; r != nullptr && i < count; ++i) {
        const hd_lookup_result& result = results[i];
        json.Open('{').Field("matched", result.matched).Field("deinflected", result.deinflected);
        json.Key("trace").Open('[');
        for (size_t j = 0; j < result.trace_count; ++j) {
            json.Open('{').Field("name", result.trace[j].name).Field("description", result.trace[j].description).Close('}');
        }
        json.Close(']');
        WriteTerm(json, result.term);
        json.Close('}');
    }
    json.Close(']');
    if (r != nullptr) hd_lookup_results_free(r);
    return Bytes(env, json.Take());
}

/**
 * The words of a line, for marking them (Amatsubame's word scanner, after Hachidori's): at each
 * kanji, katakana, or hiragana right before a kanji, the longest dictionary match after
 * deinflection (the first, most frequent, result among the longest). Only spans with a kanji,
 * or katakana words of two or more, are kept, so particles and grammar cost nothing. Each word
 * as {"s": UTF-16 start, "l": UTF-16 length, "e": headword, "r": reading}.
 */
JNIEXPORT jbyteArray JNICALL Java_app_genkan_dictionary_NativeDictionaries_scan(
    JNIEnv* env, jobject, jlong handle, jbyteArray text, jint scanLength, jbyteArray frequencyDictionary,
    jint frequencyOrder) {
    const std::string input = Utf8(env, text), freq = Utf8(env, frequencyDictionary);
    const std::vector<CodePoint> points = Decode(input);
    const hd_lookup_options options = Sorting(freq, frequencyOrder);
    Json json;
    json.Open('[');
    size_t i = 0;
    while (i < points.size()) {
        const char32_t c = points[i].value;
        const bool starts = IsKanji(c) || IsKatakana(c) || (IsHiragana(c) && i + 1 < points.size() && IsKanji(points[i + 1].value));
        if (!starts) {
            ++i;
            continue;
        }
        const size_t end = std::min(points.size(), i + static_cast<size_t>(scanLength));
        const size_t endByte = end < points.size() ? points[end].byte : input.size();
        const std::string window = input.substr(points[i].byte, endByte - points[i].byte);
        const hd_lookup_result* results = nullptr;
        size_t count = 0;
        hd_lookup_results* r = hd_lookup_run_with_options(Of(handle)->lookup, window.c_str(), 8,
                                                          static_cast<size_t>(scanLength), &options, &results, &count);
        size_t best = 0;
        const hd_lookup_result* chosen = nullptr;
        for (size_t k = 0; r != nullptr && k < count; ++k) {
            const size_t length = CodePoints(results[k].matched);
            if (length > best) {
                best = length;
                chosen = &results[k];
            }
        }
        bool keep = false;
        if (chosen != nullptr && best > 0 && i + best <= points.size()) {
            bool kanji = false, katakana = true;
            for (size_t k = i; k < i + best; ++k) {
                kanji = kanji || IsKanji(points[k].value);
                katakana = katakana && (IsKatakana(points[k].value) || points[k].value == 0x30FC);
            }
            keep = kanji || (katakana && best >= 2);
        }
        if (keep) {
            const size_t startUtf16 = points[i].utf16;
            const size_t endUtf16 = i + best < points.size() ? points[i + best].utf16
                                                              : points.back().utf16 + (points.back().value > 0xFFFF ? 2 : 1);
            json.Open('{');
            json.Key("s").Int(static_cast<long long>(startUtf16));
            json.Key("l").Int(static_cast<long long>(endUtf16 - startUtf16));
            json.Field("e", chosen->term.expression).Field("r", chosen->term.reading).Close('}');
            i += best;
        } else {
            ++i;
        }
        if (r != nullptr) hd_lookup_results_free(r);
    }
    json.Close(']');
    return Bytes(env, json.Take());
}

JNIEXPORT jbyteArray JNICALL Java_app_genkan_dictionary_NativeDictionaries_kanji(
    JNIEnv* env, jobject, jlong handle, jbyteArray character) {
    const std::string input = Utf8(env, character);
    const hd_kanji_entry* entries = nullptr;
    size_t count = 0;
    hd_kanji_results* r = hd_query_run_kanji(Of(handle)->query, input.c_str(), &entries, &count);
    Json json;
    json.Open('[');
    for (size_t i = 0; r != nullptr && i < count; ++i) {
        const hd_kanji_entry& e = entries[i];
        json.Open('{')
            .Field("dictionary", e.dict_name)
            .Field("onyomi", e.onyomi)
            .Field("kunyomi", e.kunyomi)
            .Field("tags", e.tags);
        json.Key("definitions").Open('[');
        for (size_t j = 0; j < e.definitions_count; ++j) json.Item().Str(e.definitions[j]);
        json.Close(']').Key("stats").Open('[');
        for (size_t j = 0; j < e.stats_count; ++j) {
            json.Open('{').Field("key", e.stats[j].key).Field("value", e.stats[j].value).Close('}');
        }
        json.Close(']').Close('}');
    }
    json.Close(']');
    if (r != nullptr) hd_kanji_results_free(r);
    return Bytes(env, json.Take());
}

JNIEXPORT jbyteArray JNICALL Java_app_genkan_dictionary_NativeDictionaries_styles(JNIEnv* env, jobject, jlong handle) {
    const hd_dictionary_style* styles = nullptr;
    size_t count = 0;
    hd_styles* r = hd_query_get_styles(Of(handle)->query, &styles, &count);
    Json json;
    json.Open('[');
    for (size_t i = 0; r != nullptr && i < count; ++i) {
        json.Open('{').Field("dictionary", styles[i].dict_name).Field("css", styles[i].styles).Close('}');
    }
    json.Close(']');
    if (r != nullptr) hd_styles_free(r);
    return Bytes(env, json.Take());
}

JNIEXPORT jbyteArray JNICALL Java_app_genkan_dictionary_NativeDictionaries_media(
    JNIEnv* env, jobject, jlong handle, jbyteArray dictionary, jbyteArray path) {
    const std::string dict = Utf8(env, dictionary), file = Utf8(env, path);
    const hd_media_file media = hd_query_get_media_file(Of(handle)->query, dict.c_str(), file.c_str());
    if (media.data == nullptr || media.size == 0) return nullptr;
    jbyteArray out = env->NewByteArray(static_cast<jsize>(media.size));
    if (out != nullptr) {
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(media.size), reinterpret_cast<const jbyte*>(media.data));
    }
    return out;
}

} // extern "C"
