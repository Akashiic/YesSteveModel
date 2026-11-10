#!/bin/sh
# Builds the legacy loader for the platforms the jar ships and copies the libraries into the jar resources:
#   src/main/resources/natives/linux-x86_64/libfutura3d.so   (glibc 2.17 or newer: the Crucible server)
#   src/main/resources/natives/windows-x86_64/futura3d.dll   (Windows clients: singleplayer reads .ysm too)
# Needs CMake >= 3.24, Ninja, git, a JDK (JAVA_HOME, for jni.h) and Zig (zig on PATH, or pip install ziglang).
# Dependencies (abseil, zstd, libwebp) are fetched by CMake; extra CMake arguments go in FUTURA_CMAKE_ARGS (for
# example -DFETCHCONTENT_SOURCE_DIR_ABSL=/path to use a local copy).
set -eu
here="$(cd "$(dirname "$0")" && pwd)"
out="${FUTURA_NATIVE_BUILD:-$here/build}"
resources="$here/../src/main/resources/natives"
: "${JAVA_HOME:?set JAVA_HOME to a JDK}"

build() {
    target="$1"; dir="$out/$2"; artifact="$3"; dest="$resources/$2"
    export FUTURA_ZIG_TARGET="$target"
    cmake -S "$here" -B "$dir" -G Ninja -DCMAKE_BUILD_TYPE=MinSizeRel \
        -DCMAKE_TOOLCHAIN_FILE="$here/cmake/zig-toolchain.cmake" -DFUTURA_ZIG_TARGET="$target" \
        -DJNI_INCLUDE_DIR="$JAVA_HOME/include" ${FUTURA_CMAKE_ARGS:-}
    cmake --build "$dir" --target futura3d
    mkdir -p "$dest"
    cp "$dir/$artifact" "$dest/$artifact"
    ( cd "$dest" && sha256sum "$artifact" > "$artifact.sha256" )
}

build x86_64-linux-gnu.2.17 linux-x86_64 libfutura3d.so
build x86_64-windows-gnu windows-x86_64 futura3d.dll
