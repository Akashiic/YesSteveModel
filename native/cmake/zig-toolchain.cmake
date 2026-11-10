# Cross-build with Zig's bundled clang and libc (pip install ziglang, or a zig on PATH). FUTURA_ZIG_TARGET selects
# the platform: x86_64-linux-gnu.2.17 (any glibc from 2.17, i.e. every server distribution in use) or
# x86_64-windows-gnu. The compilers are the wrappers next to this file.
# The wrappers read the target from the environment at build time too, so it must be exported (build.sh does).
if (NOT FUTURA_ZIG_TARGET)
    set(FUTURA_ZIG_TARGET "$ENV{FUTURA_ZIG_TARGET}")
endif ()
if (NOT FUTURA_ZIG_TARGET)
    message(FATAL_ERROR "Set FUTURA_ZIG_TARGET (environment)")
endif ()
set(ENV{FUTURA_ZIG_TARGET} "${FUTURA_ZIG_TARGET}")
if (FUTURA_ZIG_TARGET MATCHES "windows")
    set(CMAKE_SYSTEM_NAME Windows)
else ()
    set(CMAKE_SYSTEM_NAME Linux)
endif ()
set(CMAKE_SYSTEM_PROCESSOR x86_64)
set(CMAKE_C_COMPILER "${CMAKE_CURRENT_LIST_DIR}/zig-cc")
set(CMAKE_CXX_COMPILER "${CMAKE_CURRENT_LIST_DIR}/zig-c++")
set(CMAKE_AR "${CMAKE_CURRENT_LIST_DIR}/zig-ar")
set(CMAKE_RANLIB "${CMAKE_CURRENT_LIST_DIR}/zig-ranlib")
set(CMAKE_RC_COMPILER "${CMAKE_CURRENT_LIST_DIR}/zig-rc")
