#pragma once

#include <string>

#include "hoshidicts_c.h"

/**
 * The lookup options that sort by frequency dictionary [freq] in [order] (FrequencyOrder.native in
 * Kotlin): with no frequency dictionary, no sort. hoshidicts' AUTO is no sort dictionary at all, as
 * Yomitan with its sort dictionary unset, so a caller that wants [freq] used passes ASCENDING or
 * DESCENDING. The options point into [freq], which must outlive them.
 */
inline hd_lookup_options Sorting(const std::string& freq, int order) {
    hd_lookup_options options{};
    options.frequency_dictionary = hd_str{freq.data(), freq.size()};
    options.frequency_order = freq.empty() ? HD_LOOKUP_FREQUENCY_ORDER_DISABLED : order;
    return options;
}
