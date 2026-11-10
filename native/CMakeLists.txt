# 3D Futura Engine: legacy .ysm (v3) loader. A slim build of the YesSteveModel-Native legacy decoder (Apache-2.0,
# vendored under upstream/, see upstream/README.md) with our own JNI entry and open-format exporter (src/).
# Upstream's full library (renderer, image/audio codecs, Conan with 26 packages) is not needed for this.
cmake_minimum_required(VERSION 3.24)
project(futura3d_native CXX C)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

option(FUTURA_WITH_WEBP "Decode WebP textures of legacy models" ON)
set(JNI_INCLUDE_DIR "" CACHE PATH "Directory with jni.h (default: $JAVA_HOME/include)")

if (WIN32)
    # Zig's MinGW headers announce SDK 10.0.22621 but lack windows.globalization.h, which abseil then includes
    add_compile_definitions(_WIN32_WINNT=0x0A00 NTDDI_VERSION=0x0A000000)
endif ()

include(FetchContent)
set(FETCHCONTENT_QUIET ON)
set(BUILD_SHARED_LIBS OFF)
set(BUILD_TESTING OFF)

set(ABSL_PROPAGATE_CXX_STD ON)
set(ABSL_ENABLE_INSTALL OFF)
FetchContent_Declare(absl
    GIT_REPOSITORY https://github.com/abseil/abseil-cpp
    GIT_TAG 4447c7562e3bc702ade25105912dce503f0c4010 # 20240722.0
    GIT_SHALLOW FALSE)

set(ZSTD_BUILD_PROGRAMS OFF)
set(ZSTD_BUILD_SHARED OFF)
set(ZSTD_BUILD_STATIC ON)
set(ZSTD_BUILD_TESTS OFF)
set(ZSTD_LEGACY_SUPPORT OFF)
FetchContent_Declare(zstd
    URL https://github.com/facebook/zstd/releases/download/v1.5.7/zstd-1.5.7.tar.gz
    URL_HASH SHA256=eb33e51f49a15e023950cd7825ca74a4a2b43db8354825ac24fc1b7ee09e6fa3
    SOURCE_SUBDIR build/cmake)

set(dependencies absl zstd)
if (FUTURA_WITH_WEBP)
    set(WEBP_BUILD_ANIM_UTILS OFF)
    set(WEBP_BUILD_CWEBP OFF)
    set(WEBP_BUILD_DWEBP OFF)
    set(WEBP_BUILD_GIF2WEBP OFF)
    set(WEBP_BUILD_IMG2WEBP OFF)
    set(WEBP_BUILD_VWEBP OFF)
    set(WEBP_BUILD_WEBPINFO OFF)
    set(WEBP_BUILD_WEBPMUX OFF)
    set(WEBP_BUILD_EXTRAS OFF)
    set(WEBP_BUILD_LIBWEBPMUX OFF)
    FetchContent_Declare(webp
        GIT_REPOSITORY https://github.com/webmproject/libwebp
        GIT_TAG 845d5476a866141ba35ac133f856fa62f0b7445f # v1.4.0
        GIT_SHALLOW FALSE)
    list(APPEND dependencies webp)
endif ()
FetchContent_MakeAvailable(${dependencies})

if (CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|AMD64|amd64)$")
    set(arch_define YSM_X64)
elseif (CMAKE_SYSTEM_PROCESSOR MATCHES "^(aarch64|arm64|ARM64)$")
    set(arch_define YSM_ARM64)
else ()
    message(FATAL_ERROR "Unsupported processor ${CMAKE_SYSTEM_PROCESSOR}")
endif ()

if (NOT JNI_INCLUDE_DIR)
    if (DEFINED ENV{JAVA_HOME})
        set(JNI_INCLUDE_DIR "$ENV{JAVA_HOME}/include")
    else ()
        message(FATAL_ERROR "Set JNI_INCLUDE_DIR or JAVA_HOME")
    endif ()
endif ()
if (WIN32)
    # jni_md.h of a Windows JDK (types and JNIEXPORT), so that the DLL can be cross-built without one
    set(jni_md_dir "${CMAKE_CURRENT_SOURCE_DIR}/include/win32")
else ()
    set(jni_md_dir "${JNI_INCLUDE_DIR}/linux")
endif ()

file(GLOB legacy_sources CONFIGURE_DEPENDS
    upstream/legacy/src/v3/codec/*.cc
    upstream/legacy/src/v3/container/*.cc)
add_library(futura3d SHARED
    ${legacy_sources}
    upstream/core/buffer_managed.cc
    src/futura_shim.cc
    src/legacy_image.cc
    src/exporter.cc
    src/jni_entry.cc)
target_include_directories(futura3d PRIVATE
    shim
    upstream/core
    upstream/legacy/src
    "${JNI_INCLUDE_DIR}"
    "${jni_md_dir}")
target_compile_definitions(futura3d PRIVATE
    ${arch_define}
    YSM_ENABLE_DEBUG_LOG=0
    ABSL_MAX_VLOG_VERBOSITY=0
    $<$<BOOL:${FUTURA_WITH_WEBP}>:FUTURA_WITH_WEBP>)
target_link_libraries(futura3d PRIVATE
    absl::status
    absl::statusor
    absl::flat_hash_map
    libzstd_static)
if (FUTURA_WITH_WEBP)
    target_link_libraries(futura3d PRIVATE webpdecoder)
    target_include_directories(futura3d PRIVATE "${webp_SOURCE_DIR}/src")
endif ()
set_target_properties(futura3d PROPERTIES PREFIX "lib" OUTPUT_NAME "futura3d")
if (WIN32)
    set_target_properties(futura3d PROPERTIES PREFIX "")
    # with __declspec(dllexport) on the JNI entries, only those are exported (no auto-export)
    if (NOT CMAKE_CXX_COMPILER MATCHES "zig")
        target_link_options(futura3d PRIVATE -static -static-libgcc -static-libstdc++)
    endif ()
else ()
    # only the JNI entry points are exported; the C++ runtime is linked in
    target_link_options(futura3d PRIVATE -Wl,--version-script,${CMAKE_CURRENT_SOURCE_DIR}/src/exports.map
        -Wl,--gc-sections -Wl,-z,noexecstack)
    if (NOT CMAKE_CXX_COMPILER MATCHES "zig")
        target_link_options(futura3d PRIVATE -static-libgcc -static-libstdc++ -Wl,--exclude-libs,ALL)
    endif ()
endif ()

option(FUTURA_BUILD_TESTS "Build the native test executable" OFF)
if (FUTURA_BUILD_TESTS)
    add_executable(futura3d_native_test test/native_test.cc ${legacy_sources} upstream/core/buffer_managed.cc
        src/futura_shim.cc src/legacy_image.cc src/exporter.cc)
    target_include_directories(futura3d_native_test PRIVATE shim upstream/core upstream/legacy/src)
    target_compile_definitions(futura3d_native_test PRIVATE ${arch_define} YSM_ENABLE_DEBUG_LOG=0
        ABSL_MAX_VLOG_VERBOSITY=0 $<$<BOOL:${FUTURA_WITH_WEBP}>:FUTURA_WITH_WEBP>)
    target_link_libraries(futura3d_native_test PRIVATE absl::status absl::statusor absl::flat_hash_map
        libzstd_static)
    if (FUTURA_WITH_WEBP)
        target_link_libraries(futura3d_native_test PRIVATE webpdecoder)
        target_include_directories(futura3d_native_test PRIVATE "${webp_SOURCE_DIR}/src")
    endif ()
endif ()
