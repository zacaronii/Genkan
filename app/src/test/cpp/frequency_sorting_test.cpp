// Genkan's frequency sorting (frequency_sorting.h, as dictionary_jni.cpp uses it) on made-up
// dictionaries: two homophones, the rarer one with the higher term score, as JMdict often scores
// them. Sorted by a frequency dictionary in the order its mode gives, the common one comes first;
// with hoshidicts' AUTO (what Genkan passed before), no frequency is used and the score decides.
#include "frequency_sorting.h"
#include "hoshidicts_c.h"

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <string>
#include <utility>
#include <vector>

namespace {

uint32_t Crc32(const std::string& data) {
    uint32_t crc = 0xFFFFFFFFu;
    for (unsigned char c : data) {
        crc ^= c;
        for (int i = 0; i < 8; ++i) crc = (crc >> 1) ^ (0xEDB88320u & (0u - (crc & 1u)));
    }
    return ~crc;
}

template <typename T>
void Put(std::string& out, T value) {
    char buf[sizeof(T)];
    std::memcpy(buf, &value, sizeof(T));
    out.append(buf, sizeof(T));
}

/// A stored (uncompressed) zip of [files].
std::string Zip(const std::vector<std::pair<std::string, std::string>>& files) {
    std::string out, central;
    for (const auto& [name, data] : files) {
        const auto offset = static_cast<uint32_t>(out.size());
        const uint32_t crc = Crc32(data), size = static_cast<uint32_t>(data.size());
        Put<uint32_t>(out, 0x04034b50);
        Put<uint16_t>(out, 20);
        for (int i = 0; i < 4; ++i) Put<uint16_t>(out, 0);
        Put<uint32_t>(out, crc);
        Put<uint32_t>(out, size);
        Put<uint32_t>(out, size);
        Put<uint16_t>(out, static_cast<uint16_t>(name.size()));
        Put<uint16_t>(out, 0);
        out += name;
        out += data;
        Put<uint32_t>(central, 0x02014b50);
        Put<uint16_t>(central, 20);
        Put<uint16_t>(central, 20);
        for (int i = 0; i < 4; ++i) Put<uint16_t>(central, 0);
        Put<uint32_t>(central, crc);
        Put<uint32_t>(central, size);
        Put<uint32_t>(central, size);
        Put<uint16_t>(central, static_cast<uint16_t>(name.size()));
        for (int i = 0; i < 4; ++i) Put<uint16_t>(central, 0);
        Put<uint32_t>(central, 0);
        Put<uint32_t>(central, offset);
        central += name;
    }
    const auto start = static_cast<uint32_t>(out.size());
    out += central;
    Put<uint32_t>(out, 0x06054b50);
    Put<uint16_t>(out, 0);
    Put<uint16_t>(out, 0);
    Put<uint16_t>(out, static_cast<uint16_t>(files.size()));
    Put<uint16_t>(out, static_cast<uint16_t>(files.size()));
    Put<uint32_t>(out, static_cast<uint32_t>(central.size()));
    Put<uint32_t>(out, start);
    Put<uint16_t>(out, 0);
    return out;
}

/// Imports a dictionary made of [files] under [root]; its folder.
std::string Import(const std::filesystem::path& root, const std::string& title,
                   const std::vector<std::pair<std::string, std::string>>& files) {
    const auto zip = root / (title + ".zip");
    std::ofstream(zip, std::ios::binary) << Zip(files);
    const auto out = root / ("out-" + title);
    std::filesystem::create_directories(out);
    hd_import_result* r = hd_import(zip.c_str(), out.c_str(), 0);
    const bool ok = r != nullptr && hd_import_result_success(r);
    if (r != nullptr) hd_import_result_free(r);
    if (!ok) {
        std::fprintf(stderr, "import of %s failed\n", title.c_str());
        std::exit(1);
    }
    return (out / title).string();
}

int failures = 0;

/// The first result's headword for [text], sorted by [freq] in [order].
std::string First(const hd_lookup* lookup, const char* text, const std::string& freq, int order) {
    const hd_lookup_options options = Sorting(freq, order);
    const hd_lookup_result* results = nullptr;
    size_t count = 0;
    hd_lookup_results* r = hd_lookup_run_with_options(lookup, text, 8, 16, &options, &results, &count);
    std::string first = count > 0 ? std::string(results[0].term.expression.ptr, results[0].term.expression.len) : "";
    if (r != nullptr) hd_lookup_results_free(r);
    return first;
}

void Expect(const char* what, const std::string& got, const std::string& want) {
    if (got != want) {
        std::fprintf(stderr, "FAIL %s: got %s, want %s\n", what, got.c_str(), want.c_str());
        ++failures;
    } else {
        std::printf("ok   %s\n", what);
    }
}

} // namespace

int main() {
    const auto root = std::filesystem::temp_directory_path() / "genkan-frequency-sorting-test";
    std::filesystem::remove_all(root);
    std::filesystem::create_directories(root);

    // 器械 scores higher, as a dictionary may score a rarer word; 機会 is the everyday one.
    const std::string terms = Import(root, "Terms", {
        {"index.json", R"({"title":"Terms","revision":"1","format":3})"},
        {"term_bank_1.json", R"([["器械","きかい","","",20,["apparatus"],1,""],["機会","きかい","","",1,["chance"],2,""]])"},
    });
    // A rank list with no frequencyMode, as most Japanese lists are: lower is more common.
    const std::string ranks = Import(root, "Ranks", {
        {"index.json", R"({"title":"Ranks","revision":"1","format":3})"},
        {"term_meta_bank_1.json",
         R"([["機会","freq",{"reading":"きかい","frequency":300}],["器械","freq",{"reading":"きかい","frequency":40000}]])"},
    });
    // Occurrence counts: higher is more common.
    const std::string counts = Import(root, "Counts", {
        {"index.json", R"({"title":"Counts","revision":"1","format":3,"frequencyMode":"occurrence-based"})"},
        {"term_meta_bank_1.json",
         R"([["機会","freq",{"reading":"きかい","frequency":9000}],["器械","freq",{"reading":"きかい","frequency":12}]])"},
    });

    hd_query* query = hd_query_new();
    if (hd_query_add_term_dict(query, terms.c_str()) != 0 || hd_query_add_freq_dict(query, ranks.c_str()) != 0 ||
        hd_query_add_freq_dict(query, counts.c_str()) != 0) {
        std::fprintf(stderr, "a dictionary would not open\n");
        return 1;
    }
    hd_deinflector* deinflector = hd_deinflector_new();
    hd_lookup* lookup = hd_lookup_new(query, deinflector);

    Expect("a rank list, lowest first: the common word", First(lookup, "きかいがある", "Ranks", HD_LOOKUP_FREQUENCY_ORDER_ASCENDING), "機会");
    Expect("occurrence counts, highest first: the common word",
           First(lookup, "きかいがある", "Counts", HD_LOOKUP_FREQUENCY_ORDER_DESCENDING), "機会");
    Expect("AUTO (no sort dictionary): the higher score", First(lookup, "きかいがある", "Ranks", HD_LOOKUP_FREQUENCY_ORDER_AUTO), "器械");
    Expect("no frequency dictionary: the higher score", First(lookup, "きかいがある", "", HD_LOOKUP_FREQUENCY_ORDER_ASCENDING), "器械");

    hd_lookup_free(lookup);
    hd_deinflector_free(deinflector);
    hd_query_free(query);
    std::filesystem::remove_all(root);
    return failures == 0 ? 0 : 1;
}
