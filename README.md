# Biomes Mod

A Fabric mod that adds biomes to the End. It targets Minecraft **1.21.1** and **26.2** with Mojmap names.

| Biome | Where | Notes |
|---|---|---|
| `biomesmod:chorus_thicket` | End highlands (weight 0.5 vs vanilla 1.0) | Dense chorus forest, violet fog, portal particles |

## Layout

```
common/                 shared Java + resources (compiled into every version)
  datapack/             pack.mcmeta per version for the standalone data pack
versions/1.21.1/        Loom (remap) + Mojang mappings, Java 21, version-specific JSON + shims
versions/26.2/          Loom (unobfuscated), Java 25, version-specific JSON + shims
docs/api-differences.md worldgen API differences between the two versions
```

To add a version, create `versions/<mc>/` (gradle.properties, build.gradle, `compat/Ids.java`, biome JSON) and add it to `settings.gradle`.

## Build and verify

```bash
./gradlew build datapack                 # jars -> versions/*/build/libs, data packs -> versions/*/build/datapack
./gradlew :1.21.1:runSelfCheck :26.2:runSelfCheck
```

`runSelfCheck` boots Fabric's headless gametest server, so it needs no EULA and no client. For each biome it checks three things:

1. The biome is registered.
2. The End biome source can produce it.
3. A locate search finds it in generated End terrain.

It exits non-zero on any failure.

For interactive testing, run `./gradlew :26.2:runClient` (or `:1.21.1:runClient`).

## Paper / vanilla servers

Paper cannot load Fabric mods. The `datapack` task builds a data-only pack with the biome and feature definitions. Vanilla End generation has no hook for extra biomes, so on Paper the biome is registered but does not generate naturally. To inspect it:

```
/execute in minecraft:the_end run fillbiome ~-32 0 ~-32 ~32 255 ~32 biomesmod:chorus_thicket
```
