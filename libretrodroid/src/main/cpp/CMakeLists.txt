cmake_minimum_required(VERSION 3.13.2)

# now build app's shared lib
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++17 -Wall")

add_definitions("-DVFS_FRONTEND -DHAVE_STRL")

# Oboe from its prefab package (Genkan: instead of a submodule).
find_package (oboe REQUIRED CONFIG)

# Genkan: libchdr (third_party/libchdr, a submodule, with its own lzma, zlib and zstd) and the one sector
# of a disc image read through it (discsector.cpp). Both come before libretro-common's include directory,
# whose include/libchdr is an older libchdr's headers: they mustn't see it.
# The system's zlib: libchdr's own would rename a header in the submodule as it builds.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WITH_SYSTEM_ZLIB ON CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libchdr libchdr EXCLUDE_FROM_ALL)
add_library(genkan_disc STATIC discsector.h discsector.cpp)
target_link_libraries(genkan_disc PRIVATE chdr-static)

include_directories("libretro/libretro-common/include")

# Genkan: the Yomikata reader runtime (third_party/yomikata, a submodule), linked in whole: only its yk_
# functions are used, from scripthost.cpp, and Lua stays hidden inside this library.
set(YOMIKATA_READER_SHARED OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/yomikata/runtime yomikata EXCLUDE_FROM_ALL)

set (LIBRETRO_COMMON
        libretro/libretro-common/vfs/vfs_implementation.c
        libretro/libretro-common/string/stdstring.c
        libretro/libretro-common/encodings/encoding_utf.c
        libretro/libretro-common/file/file_path.c
        libretro/libretro-common/time/rtime.c
)

add_library(libretrodroid SHARED
        libretrodroidjni.h
        libretrodroidjni.cpp
        libretrodroid.h
        libretrodroid.cpp
        log.h
        core.h
        core.cpp
        video.h
        video.cpp
        immersivemode.h
        immersivemode.cpp
        videolayout.h
        videolayout.cpp
        renderers/renderer.h
        renderers/renderer.cpp
        renderers/es3/es3utils.h
        renderers/es3/es3utils.cpp
        renderers/es3/framebufferrenderer.h
        renderers/es3/framebufferrenderer.cpp
        renderers/es2/imagerendereres2.h
        renderers/es2/imagerendereres2.cpp
        renderers/es3/imagerendereres3.h
        renderers/es3/imagerendereres3.cpp
        audio.h
        audio.cpp
        resamplers/resampler.h
        resamplers/linearresampler.h
        resamplers/linearresampler.cpp
        resamplers/sincresampler.h
        resamplers/sincresampler.cpp
        fpssync.h
        fpssync.cpp
        environment.h
        environment.cpp
        input.h
        input.cpp
        shadermanager.h
        shadermanager.cpp
        rumble.h
        rumble.cpp
        rumblestate.h
        rumblestate.cpp
        utils/javautils.h
        utils/javautils.cpp
        utils/utils.cpp
        utils/utils.h
        utils/jnistring.h
        utils/jnistring.cpp
        utils/libretrodroidexception.h
        utils/libretrodroidexception.cpp
        utils/rect.h
        utils/rect.cpp
        errorcodes.h
        errorcodes.cpp
        genkantap.h
        genkantap.cpp
        genkantapjni.cpp
        framestats.h
        framestats.cpp

        vulkanbridge.h
        vulkanbridge.cpp
        scripthost.h
        scripthost.cpp
        scriptjni.cpp
        vfs/vfs.h
        vfs/vfs.cpp
        vfs/vfsinterfacenegotiation.h
        vfs/vfsfile.h
        vfs/vfsfile.cpp
        vfs/fdwrapper.h
        vfs/fdwrapper.cpp
        microphone/microphone.h
        microphone/microphone.cpp
        microphone/microphoneinterface.h
        microphone/microphoneinterface.cpp
        ${LIBRETRO_COMMON}
)

# Support 16k pages for Android 15
target_link_options(libretrodroid PRIVATE "-Wl,-z,max-page-size=16384")

# add lib dependencies
target_link_libraries(libretrodroid
                      android
                      log 
                      EGL
                      oboe::oboe
                      GLESv3
                      yomikata_reader
                      genkan_disc
)
