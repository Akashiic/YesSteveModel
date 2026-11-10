# Vendored YesSteveModel-Native sources

From [YesSteveModel-Native](https://github.com/YesSteveModel/YesSteveModel-Native), branch `dev`, commit
`907d1c653b7eb85ee92ee11305134408300797f8`, Apache License 2.0 (`LICENSE` here). Only what the legacy v3 decoder
needs, unchanged:

| Here | Upstream |
|---|---|
| `legacy/src/v3/codec/*` | `modules/legacy/src/v3/codec/*` (envelope: CityHash fork, XChaCha with the historical parameter updates, mt19937 XOR, dirty zstd) |
| `legacy/src/v3/container/{decoder.cc,decoder.h,image.h,model.h}` | `modules/legacy/src/v3/container/*` (the historical container, versions 1 to 32) |
| `core/*` | `modules/core/src/{buffer*,err,pimpl,inline,macro,compiler,assume,non_copyable,move_only,system_allocator}*` |

Not taken: `container/image.cc` (re-encodes images for upstream's container; replaced by `../src/legacy_image.cc`),
`conversion/projector.cc` (maps to upstream's protobuf schema; replaced by `../src/exporter.cc`), `bundle.cc` (the
v1/v2 bundles, ported to Java in `com.akashic.futura.model.legacy.LegacyBundle`), and the JNI glue of
`modules/lib-jni` (bound to `com.elfmcys.ysm` classes; replaced by `../src/jni_entry.cc`). Headers of `ysm-core` and
`ysm-codec` that pull in cpu_features, mimalloc or the image/audio codecs are replaced by `../shim/`.

To update: copy the same files from a newer commit, rebuild with `../build.sh`, run the native test and the Java tests.
