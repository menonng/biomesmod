# Biomes Mod

A Fabric mod that adds Overworld, Nether and End biomes, each loosely inspired by a real place. It targets Minecraft **1.21.1** and **26.2** with Mojmap names.

**Requires:** Fabric API and [TerraBlender](https://modrinth.com/mod/terrablender). TerraBlender places the biomes in every dimension.

| Biome | Dimension | Motif | Placement |
|---|---|---|---|
| `biomesmod:gotjawal` | Overworld | Jeju Gotjawal: humid lava-rock forest | Takes Forest's climate slot inside this mod's TerraBlender regions |
| `biomesmod:danakil_flats` | Nether | Danakil Depression: salt crust, sulfur haze, lava pools | Hot and dry corner of this mod's Nether region; calcite/basalt surface rule |
| `biomesmod:chorus_thicket` | End | Dense chorus forest | End highlands, weight 5 (vanilla highlands is 10) |

## Layout

```
common/                 shared Java + resources (compiled into every version)
  src/gametest/         dev-only: mixin that gives the self-check a normal (non-flat) world
  datapack/             pack.mcmeta per version for the standalone data pack
versions/1.21.1/        Loom (remap) + Mojang mappings, Java 21, version-specific JSON + compat/ shims
versions/26.2/          Loom (unobfuscated), Java 25, version-specific JSON + compat/ shims
docs/api-differences.md worldgen API differences between the two versions
```

To add a biome:

1. Write one biome JSON per version.
2. Add its key and dimension to `ModBiomes.ALL`.
3. Place it in a region (or `EndBiomeRegistry`) in `worldgen/`.

To add a Minecraft version, create `versions/<mc>/` (gradle.properties, build.gradle, `compat/` shims, biome JSON) and add it to `settings.gradle`.

## Build and verify

```bash
./gradlew build datapack                 # jars -> versions/*/build/libs, data packs -> versions/*/build/datapack
./gradlew :1.21.1:runSelfCheck :26.2:runSelfCheck
```

`runSelfCheck` boots Fabric's headless gametest server on a fresh normal world, so it needs no EULA and no client. For each biome it checks three things in that biome's dimension:

1. The biome is registered.
2. The dimension's biome source can produce it.
3. A locate search finds it in generated terrain.

It also logs the most common surface blocks found there. It exits non-zero on any failure.

For interactive testing, run `./gradlew :26.2:runClient` (or `:1.21.1:runClient`).

## Paper / vanilla servers

Paper cannot load Fabric mods or TerraBlender. The `datapack` task builds a data-only pack with the biome and feature definitions. On Paper the biomes are registered but do not generate naturally, and surface rules do not apply. To inspect one, force-load the chunk first, then fill the biome:

```
/forceload add 0 0
/fillbiome 0 60 0 15 90 15 biomesmod:gotjawal
```
