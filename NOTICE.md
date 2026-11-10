# NOTICE

## 3D Futura Engine

3D Futura Engine is a fork of [Yes Steve Model](https://github.com/YesSteveModel/YesSteveModel)
(branch `dev/1.20`, commit `15c2c25f5b50f54ee099bc474483dc4b588d956d`), Copyright 2026 YesSteveModel contributors,
licensed under the Apache License 2.0 (see `LICENSE`). It is not affiliated with or endorsed by the Yes Steve Model
team, and "Yes Steve Model" is not used as the name of this product.

Changes made in this fork:

* the project was moved to Minecraft Forge 1.7.10 (Java 8 bytecode); the upstream 1.20 code remains unchanged on
  branch `dev/1.20`, and files brought over from it are adapted and say so in their header;
* legacy binary `.ysm` models are read strictly in memory alongside the open authoring format: the v1/v2 bundles by a
  Java port of `modules/legacy/src/bundle.cc`, the encrypted v3 container by the official legacy decoder of
  [YesSteveModel-Native](https://github.com/YesSteveModel/YesSteveModel-Native) (Apache-2.0, commit `907d1c6`),
  vendored under `native/upstream/` and built as the slim library `natives/*/libfutura3d.so` / `futura3d.dll` with our
  own JNI entry and exporter (`native/src/`). Everything linked into that library (the CityHash fork and ChaCha code
  inside the decoder, Abseil, Zstandard, libwebp, the C++ runtime) is listed with its licence text in
  `natives/THIRD_PARTY_LICENSES.txt`, shipped in the jar next to the libraries;
* the built-in models `wine_fox/09_hailuo`, `wine_fox/10_zhiban` and `wine_fox/17_mini` (All Rights Reserved) were
  removed; the remaining built-in models are under `assets/futura3d/builtin` and keep the licence and authors written
  in each `ysm.json` (CC0 or CC BY-NC-SA 4.0); they are not covered by the Apache License;
* the upstream documentation is kept as reference under `docs/upstream/`.

Third-party libraries bundled in this fork's release jar:

| Library | Version | License | Where | Licence text |
|---|---|---|---|---|
| [Concentus](https://github.com/lostromb/concentus) (Java port of libopus; Maven `io.github.jaredmdobson:concentus`) | 1.0.2 | BSD-3-Clause (Opus/Concentus terms) | shaded, relocated to `com.akashic.futura.shadow.io.github.jaredmdobson.concentus`; decodes Ogg Opus model sounds | `licenses/concentus` (in the jar; `src/main/resources/licenses/concentus` in the repository) |

The upstream NOTICE follows unchanged (paths in it refer to the upstream tree).

---

# 注意

## 内置模型许可

内置模型资产不属于 Apache License 2.0。

`assets/ysm/builtin/**/ysm.json` 中的 `metadata.license` 是每个内置模型资产的权威许可证声明，`metadata.authors` 保留其作者与归属信息。

## 第三方许可证与来源声明

本仓库包含通过源码拷贝、修改或构建期 shading 纳入的第三方作品。第三方作品保留其原始许可证和版权声明，不因位于本仓库内而改为 Apache License 2.0。

### 直接拷贝或修改的 Java 源码

| 仓库内范围 | 上游项目 | 许可证 | 快照说明 | 随包许可证 |
|---|---|---|---|---|
| `com/elfmcys/ysm/lib/concentus` | [Concentus](https://github.com/lostromb/concentus) | BSD-style Opus/Concentus terms | 无法从当前源码证明确切 revision | `licenses/concentus` |
| `com/elfmcys/ysm/lib/gagravarr` | [VorbisJava / Gagravarr](https://github.com/Gagravarr/VorbisJava) | Apache-2.0 | 无法从当前源码证明确切 revision | `licenses/vorbis-java-core` |
| `com/elfmcys/ysm/geckolib3` | [GeckoLib](https://github.com/bernie-g/geckolib) | MIT | 包含本项目修改；无法从当前源码证明确切 revision | `licenses/geckolib` |
| `com/elfmcys/ysm/mclib` | [McLib](https://github.com/mchorse/mclib) | MIT | 无法从当前源码证明确切 revision | `licenses/mclib` |
| `com/elfmcys/ysm/molang` | [Mocha](https://github.com/unnamed/mocha) | MIT | 包含本项目修改；无法从当前源码证明确切 revision | `licenses/molang` |

### 随包依赖

构建会将 QuickBuffers runtime relocate 后打入最终制品，其 Apache-2.0 文本位于 `licenses/quickbuffers`。

`licenses/` 还包含随 Java 制品分发的 native 与其他第三方组件的许可证文本。

Legacy native importer 内嵌了格式兼容所需的修改版 CityHash 64-bit 实现与
ChaCha/HChaCha 参考实现。其来源和许可分别见 `licenses/cityhash` 与
`licenses/legacy-chacha`；这些声明必须与三个平台的主 native 库一同分发。

Native 项目的第三方组件、版本和许可证清单由其自身仓库维护：[THIRD_PARTY_LICENSES.md](https://github.com/YesSteveModel/YesSteveModel-Native/blob/dev/THIRD_PARTY_LICENSES.md)。

本清单是合规辅助材料，不替代各上游许可证正文。
