# 3D Futura Engine: context for whoever works in this repository

Forge 1.7.10 mod: custom Bedrock player models, a backport of Yes Steve Model (YSM, Apache-2.0)
for the TEOF modpack on Crucible (Java 21 server, Java 8 clients).
Sibling project: [Kunftig](https://github.com/Akashiic/Kunftig) (its `docs/playermodel/ESTUDO-YSM.md`
is the architectural study this port follows).

## Project principles and model licensing

- Supported formats:
  - Primary format: the open authoring format (folder or zip with `ysm.json` spec 2).
  - Legacy compatibility: binary model files (`.ysm`) supported via the official upstream
    native compatibility loader (`YesSteveModel-Native`, Apache-2.0). Deserialized geometry
    and textures remain strictly in-memory during server runtime and client sync.
- No third-party model that is not redistributable goes into the repository. Models used for
  local testing stay out of git (`.gitignore`). Built-in models retain their license in their
  respective metadata; All Rights Reserved assets are never bundled.
- Attribution: "Yes Steve Model" is not the product name. Keep `LICENSE` and `NOTICE.md`; any
  file adapted from upstream must state so in its header
  (`Adapted from Yes Steve Model, <upstream path>, Apache-2.0`).

## Where things are

- `main` is the 1.7.10 port; `dev/1.20` is the untouched upstream. Bring files over with
  `git show dev/1.20:<path>` and adapt them; document additions in `docs/PORTING.md`.
- Code: `src/main/java/com/akashic/futura/`.
- Built-in models: `src/main/resources/assets/futura3d/builtin/`.
- Native libraries: bundled in JAR resources under `natives/` for Linux x86_64 and Windows x64.
- Upstream docs (Chinese) are reference under `docs/upstream/`: the format standard is
  `docs/upstream/standards/` (CC0), and Molang is under `docs/upstream/architecture/animation/`.

## Build rules and constraints

- Gradle (GTNH convention / RetroFuturaGradle). Runtime code targets **Java 8 bytecode** (Jabel):
  modern Java syntax is acceptable, but no Java 9+ standard library APIs (`record`, `sealed`,
  `List.of`, `Map.of`, `Stream.toList()`, etc.) in `src/main`. Upstream uses all of them: convert
  when porting.
- Minecraft 1.7.10 ships Gson 2.2.4: no `@JsonAdapter` (adapters must be registered in
  `format/FormatGson`), no `TypeToken.getParameterized`, no `JsonParser.parseString`, and no
  `JsonObject.keySet`.
- Encoding: Some built-in models have non-ASCII filenames: build with a UTF-8 locale
  (`export LANG=C.UTF-8`), otherwise Gradle will fail during resource hashing.
- Pure logic (format, geometry, animation, Molang, physics) must not depend on Minecraft
  classes and must be covered by unit tests.
- Code style: `./gradlew spotlessApply build` must pass. If Maven Central returns HTTP 429
  through a session proxy, rerun with `--max-workers=1` and retry.
- **Render harness** (`com.akashic.futura.dev`, never included in the release jar; packed only
  with `-Pharness`): with a `run/client/futura3d-harness` file present,
  `./gradlew runClient -Pharness` loads a flat world, saves screenshots to
  `run/client/screenshots/futura3d-*.png`, writes benchmarks to
  `run/client/futura3d-harness.txt`, and terminates.
  Headless execution: `xvfb-run -a -s "-screen 0 1280x720x24 +extension RANDR"` with
  `LIBGL_ALWAYS_SOFTWARE=1`; LWJGL 2 requires `xrandr` (`apt-get install -y x11-xserver-utils`).
- Any feature not yet verified in-game must be documented as "to be checked" in `docs/`.
