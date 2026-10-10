# src2mc NeoForge mod

The companion mod that plays converted Source maps: bundle loading and
validation, mod-native placement (`/src2mc place <map>`), exact per-cell
surface rendering on mod-owned paged textures, every prop as its real mesh,
sub-block collision, the map's baked Source light with bump-mapped lightmaps,
cubemap reflections, detail textures, blended displacements and
self-illumination, Source's HDR post-processing (auto exposure, bloom, colour
correction, vignette, fog), the 2D and 3D skybox, `.pcf` particles, impacts and
sparks, the map's sound, and its logic running on the server, with moving brush
entities as Sable sub-levels.
What it does and how to use it is in the [root README](../README.md#the-companion-mod).

The mod registers fixed generic world content plus `/src2mc status`, `validate`,
`reload`, `reconcile` and `place`; client diagnostics include
`/src2mc_render_status`, `/src2mc_prop_status`,
`/src2mc_prop_overlay_toggle` and `/src2mc_audio`; the map's logic is started with `/src2mc logic start` (see the main README). Bundles load by themselves in the background
during game startup, so `/src2mc reload` is only needed for a bundle that
changed on disk — see [`docs/bundle-loading.md`](docs/bundle-loading.md). The map
is drawn with the mod's own core shaders, which Iris shader packs do not run,
so play maps without a shader pack active (decision D28); what a shaderpack does
to the buffers the mod uploads is in [`docs/iris-compat.md`](docs/iris-compat.md).

The current implementation state, test paths, verified behavior, known defects,
and next work are recorded in [`../SESSION_HANDOFF.md`](../SESSION_HANDOFF.md).

This is a clean restart after an unplanned prototype was discarded. The current
requirements, architecture decisions and phased implementation sequence are:

- [`../docs/mod-requirements.md`](../docs/mod-requirements.md)
- [`../docs/decisions.md`](../docs/decisions.md)
- [`IMPLEMENTATION_PLAN.md`](IMPLEMENTATION_PLAN.md)

The old surface-pool bundle format is explicitly retired in
[`../docs/format.md`](../docs/format.md).

Export one or several maps from the repository root with:

```sh
cargo run -- mod export --campaign hl2 --out out path/to/map1.bsp path/to/map2.bsp
```

Textures are kept at up to 16 texels per block by default. `--quality full`
keeps every texture at its original resolution, at a much larger bundle and
texture memory cost (INFRA's furnace: 36 MB and 1 atlas page by default,
1.1 GB and 49 pages at full quality). Raise `textureVramBudgetBytes` and
`textureRamBudgetBytes` in the mod config if pages fail to load.

Sound is exported by default, as Ogg Vorbis at quality 7 (INFRA's furnace: 308
sounds, 14.6 MB); `--no-audio` leaves it out. The mod plays it through
Minecraft's sound engine by placing decoded buffers in the engine's own buffer
cache under a namespace of its own (`client/audio/SoundLibrary.java`).

## Requirements

- A Java 21 JDK.
- Network access the first time Gradle resolves the wrapper and NeoForge
  development dependencies.

## Build

From this directory:

```sh
./gradlew build
```

The development JAR is written to `build/libs/src2mc-<VERSION>.jar`; the
version comes from the repository's root `VERSION` file.

Run the JVM unit tests with:

```sh
./gradlew test
```

## Run the development client

```sh
./gradlew runClient
```

The client uses `runs/client/` as its game directory, with bundles in
`runs/client/config/src2mc/bundles/` and schematics in
`runs/client/config/src2mc/schematics/`. Its mods folder holds the test pack
(Sodium, Iris, Create and others) the mod is checked against.

Exact target versions and the testing arrangement for Sodium, Lithium, Create,
Create: Aeronautics, and Sable belong in
[`docs/compatibility-baseline.md`](docs/compatibility-baseline.md) once supplied
by the user.

Local screenshots, logs, and benchmark reports go in `local-reports/`, which is
ignored by Git. Do not add proprietary game assets to this project.
