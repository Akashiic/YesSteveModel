# Native legacy loader (`libfutura3d`)

Reads encrypted legacy `.ysm` files (v3: the binary export of YSM 1.x/2.x with the UTF-8 BOM + `YSGP` header) in
memory and returns the files of an equivalent open-format model to Java (`com.akashic.futura.natives.LegacyNative`).
The v1/v2 raw bundles (`YSGP` without BOM) do not need it: they are read in Java.

* `upstream/`: the official decoder from YesSteveModel-Native (Apache-2.0), see `upstream/README.md`.
* `shim/`: small replacements for the upstream headers that would pull in cpu_features, mimalloc and the codecs.
* `src/`: our code: `exporter.cc` (legacy model → `ysm.json`, Bedrock geometry with `poly_mesh`, animations,
  controllers, images, sounds, functions, languages), `legacy_image.cc`, `futura_shim.cc`, `jni_entry.cc`.
* `test/native_test.cc`: the envelope against upstream's frozen vector (`test/data`) and the exporter on a synthetic
  v32 model; it also writes `src/test/resources/legacy/synthetic-v32.f3dl`, which the Java tests load.

## Build

```bash
pip install ziglang
```

```bash
JAVA_HOME=/path/to/jdk ./build.sh
```

`build.sh` cross-builds with Zig (one toolchain for both targets) and copies the results, with their `.sha256`, to
`src/main/resources/natives/`:

| Target | File | Runs on |
|---|---|---|
| `x86_64-linux-gnu.2.17` | `linux-x86_64/libfutura3d.so` | any x86_64 Linux with glibc 2.17+ (the Crucible server) |
| `x86_64-windows-gnu` | `windows-x86_64/futura3d.dll` | Windows 10/11 x64 (the Universal C Runtime; Windows 7/8.1 need KB2999226) |

Dependencies (abseil 20240722.0, zstd 1.5.7, libwebp 1.4.0) are fetched by CMake; pass local copies with
`FUTURA_CMAKE_ARGS="-DFETCHCONTENT_SOURCE_DIR_ABSL=... -DFETCHCONTENT_SOURCE_DIR_ZSTD=... -DFETCHCONTENT_SOURCE_DIR_WEBP=..."`.

Native test (host build):

```bash
cmake -S . -B build/test -G Ninja -DFUTURA_BUILD_TESTS=ON && cmake --build build/test --target futura3d_native_test
```

```bash
build/test/futura3d_native_test test/data ../src/test/resources/legacy/synthetic-v32.f3dl
```

Licences of everything linked in: `src/main/resources/natives/THIRD_PARTY_LICENSES.txt` (shipped in the jar).
