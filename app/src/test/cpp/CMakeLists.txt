cmake_minimum_required(VERSION 3.31)

# Host tests of the native lookup Genkan runs (built and run by the app's nativeLookupTest task).
project(genkan_native_tests LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(HOSHIDICTS_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/hoshidicts")
add_subdirectory("${HOSHIDICTS_ROOT}" "${CMAKE_CURRENT_BINARY_DIR}/hoshidicts" EXCLUDE_FROM_ALL)

add_executable(frequency_sorting_test frequency_sorting_test.cpp)
target_include_directories(frequency_sorting_test PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp")
target_link_libraries(frequency_sorting_test PRIVATE hoshidicts)
