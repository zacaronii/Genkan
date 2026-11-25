cmake_minimum_required(VERSION 3.31)

project(genkan_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Optimised in debug builds too: unoptimised, a JMdict import takes 17 s on the Thor, not 1.
add_compile_options("$<$<CONFIG:Debug>:-O2>")

# bee-san's hoshidicts, pinned as a submodule: the engine and index layout Hachidori uses.
set(HOSHIDICTS_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/hoshidicts")
add_subdirectory("${HOSHIDICTS_ROOT}" "${CMAKE_CURRENT_BINARY_DIR}/hoshidicts" EXCLUDE_FROM_ALL)

add_library(genkan_dictionary SHARED dictionary_jni.cpp)
target_link_libraries(genkan_dictionary PRIVATE hoshidicts)
# Dictionaries are mapped into memory; 16 KB pages keep the library loadable on newer kernels.
target_link_options(genkan_dictionary PRIVATE "-Wl,-z,max-page-size=16384")

# The Yomikata script host in Genkan's own process, for games in other emulators (ARMSX2 through
# PINE): libretrodroid's scripthost.cpp and the reader runtime, the same the built-in player runs.
set(YOMIKATA_READER_SHARED OFF CACHE BOOL "" FORCE)
add_subdirectory("${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/yomikata/runtime" "${CMAKE_CURRENT_BINARY_DIR}/yomikata" EXCLUDE_FROM_ALL)
set(SCRIPT_HOST_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../libretrodroid/src/main/cpp")
add_library(genkan_script SHARED script_jni.cpp "${SCRIPT_HOST_DIR}/scripthost.cpp")
target_include_directories(genkan_script PRIVATE "${SCRIPT_HOST_DIR}")
target_link_libraries(genkan_script PRIVATE yomikata_reader)
target_link_options(genkan_script PRIVATE "-Wl,-z,max-page-size=16384")
