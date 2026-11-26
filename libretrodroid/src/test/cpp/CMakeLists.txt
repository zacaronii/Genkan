# Genkan: the Yomikata script host (src/main/cpp/scripthost.cpp) with the reader runtime, built for this
# machine and run on made-up memory. app/build.gradle.kts's scriptHostTest builds and runs it.
cmake_minimum_required(VERSION 3.20)
project(script_host_test LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(YOMIKATA_READER_SHARED OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/yomikata/runtime yomikata EXCLUDE_FROM_ALL)

add_executable(script_host_test script_host_test.cpp ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp/scripthost.cpp)
target_include_directories(script_host_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp)
target_link_libraries(script_host_test PRIVATE yomikata_reader)

# The part of a hardware core's framebuffer its frame filled (src/main/cpp/renderers/framefill.h).
add_executable(frame_fill_test frame_fill_test.cpp)
target_include_directories(frame_fill_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp/renderers)

# A console's address read through a core's memory descriptor (src/main/cpp/memorymap.h).
add_executable(memory_map_test memory_map_test.cpp)
target_include_directories(memory_map_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp)

# Where a touch leaves a core's pointer (src/main/cpp/pointer.h).
add_executable(pointer_test pointer_test.cpp)
target_include_directories(pointer_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp)

# The game a core names through Genkan's private call (src/main/cpp/gameid.h).
add_executable(game_id_test game_id_test.cpp)
target_include_directories(game_id_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp)

# When the frame loop holds a core back by its sound buffer (src/main/cpp/audiopace.h).
add_executable(audio_pace_test audio_pace_test.cpp)
target_include_directories(audio_pace_test PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp)
