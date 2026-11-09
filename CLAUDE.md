# 3D Futura Engine: context for whoever works in this repository

Forge 1.7.10 mod: custom Bedrock player models, a fork of Yes Steve Model (YSM, Apache-2.0) for the TEOF modpack.
Sibling project: [Kunftig](https://github.com/Akashiic/Kunftig) (its `docs/playermodel/ESTUDO-YSM.md` is the study
this port follows).

## Rules that are not negotiable

* Only the **open** authoring format is read (folder or zip with `ysm.json`). Never read, decrypt or convert encrypted
  `.ysm` exports, never bring over the upstream native library or its legacy importer
  (`natives/legacy`, `YesSteveModel-Native/modules/legacy`).
* No third-party model that is not redistributable goes into the repository. Models a user only has for testing stay
  out of git. Built-in models keep the licence in their `ysm.json`; All Rights Reserved ones are not bundled.
* "Yes Steve Model" is not the product name. Keep `LICENSE` and `NOTICE.md`; a file adapted from upstream says so in
  its header (`Adapted from Yes Steve Model, <upstream path>, Apache-2.0`).

## Where things are

* `main` is the 1.7.10 port; `dev/1.20` is the untouched upstream. Bring a file over with
  `git show dev/1.20:<path>` and adapt it; record it in `docs/PORTING.md`.
* Code: `src/main/java/com/akashic/futura/`. Built-in models: `src/main/resources/assets/futura3d/builtin/`.
* Upstream docs (Chinese) are reference under `docs/upstream/`: the format standard is `docs/upstream/standards/`
  (CC0), the Molang language `docs/upstream/architecture/animation/`.

## Build and rules

* Gradle (GTNH convention / RetroFuturaGradle). Runtime code targets **Java 8 bytecode** (Jabel): modern syntax is fine,
  but no `record`, `sealed`, `List.of`, `Map.of`, `Stream.toList()` or other Java 9+ library APIs in `src/main`.
  Upstream uses all of them: convert when porting.
* Minecraft 1.7.10 ships Gson 2.2.4: no `@JsonAdapter` (adapters are registered in `format/FormatGson`), no
  `TypeToken.getParameterized`, no `JsonParser.parseString`, no `JsonObject.keySet`.
* Some built-in models have non-ASCII file names: build with a UTF-8 locale (`export LANG=C.UTF-8`), or Gradle fails
  hashing resources.
* Pure logic (format, geometry, animation, Molang, physics) has no Minecraft classes and is unit-tested.
* `./gradlew spotlessApply build`: spotless must pass. Maven Central may answer 429 through the session proxy; rerun
  with `--max-workers=1` and retry.
* What is not verified in game is written as "to be checked" in the docs.
