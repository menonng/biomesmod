# Worldgen / biome API: 1.21.1 vs 26.2 (Mojmap)

Sources: decompiled 1.21.1 and 26.2 with minecraft-dev-cli (Mojmap) plus the FabricMC/fabric branches `1.21.1` and `26.2`.

## Toolchain

| | 1.21.1 | 26.2 |
|---|---|---|
| Obfuscated jar | yes | **no** (ships with Mojang names) |
| Loom plugin | `net.fabricmc.fabric-loom-remap` + `loom.officialMojangMappings()` | `net.fabricmc.fabric-loom` (no `mappings` line) |
| Dependency config | `modImplementation` | `implementation` |
| Java | 21 | 25 |
| Data pack format | `pack_format: 48` | `min_format`/`max_format: [107, 1]` |

## Java API

| Concept | 1.21.1 | 26.2 |
|---|---|---|
| Namespaced id | `ResourceLocation.fromNamespaceAndPath` | `Identifier.fromNamespaceAndPath` (class renamed) |
| Id of a key | `ResourceKey#location()` | `ResourceKey#identifier()` |
| `Biome` codec | climate + `effects` + generation + spawns | adds `attributes` (`EnvironmentAttributeMap`, positional-only) |
| `BiomeSpecialEffects` | fog, water, water fog, sky, foliage, grass, particle, ambient/mood/additions sound, music | only `water_color`, `foliage_color`, `dry_foliage_color`, `grass_color`, `grass_color_modifier` |
| Fog, sky, particles, sounds, music | fields on `BiomeSpecialEffects` | `EnvironmentAttributes` (`visual/*`, `audio/*`), set per biome **or** per dimension type |
| End sky and fog defaults | hard-coded `DimensionSpecialEffects.EndEffects` | `dimension_type/the_end.json` attributes (`skybox: "end"`) |
| `TheEndBiomeSource` | 5 fixed holders | unchanged in shape |
| Fabric `TheEndBiomes` | `addHighlandsBiome/addMidlandsBiome/addBarrensBiome/addSmallIslandsBiome/addMainIslandBiome(ResourceKey<Biome>, double)` | **identical** |
| Fabric gametest | `FabricGameTest` + vanilla `@GameTest`; server needs ≥1 test | Fabric `@GameTest`; server boots with 0 tests |

| Lowest build Y | `LevelHeightAccessor#getMinBuildHeight()` | `#getMinY()` |
| `SurfaceRules.isBiome` | `isBiome(ResourceKey...)` | `isBiome(HolderGetter<Biome>, ResourceKey...)` |
| Surface noise condition | `noiseCondition(...)` | `noiseCondition2d(...)` / `noiseCondition3d(...)` |

Unchanged in both: `Registries.BIOME`, `ResourceKey.create`, `Level.END`, `ServerLevel#findClosestBiome3d`, `BiomeSource#possibleBiomes`, and the `HolderGetter#get` lookup.

## Biome JSON

| Field | 1.21.1 | 26.2 |
|---|---|---|
| Colors | int (`5974638`) | string (`"#5b2a6e"`) |
| `effects.fog_color` / `sky_color` / `water_fog_color` | required in `effects` | moved to `attributes["minecraft:visual/…"]` |
| `effects.particle` | `{ "options": {type}, "probability" }` | `attributes["minecraft:visual/ambient_particles"]: [{ "particle": {type}, "probability" }]` |
| `mood_sound`, `music`, … | in `effects` | `minecraft:audio/ambient_sounds`, `minecraft:audio/background_music` |
| `carvers` | map (`{}`) | list or tag (`[]`) |
| `features`, `spawners`, `spawn_costs`, climate | same | same |
| placed / configured features | same | same (shared in `common/`) |

## Consequences for this repo

- Java code is shared (`common/`). Each version adds one shim, `compat/Ids.java`.
- Biome JSON is per version (`versions/<mc>/src/main/resources/data/biomesmod/worldgen/biome/`).
- Placed and configured features, lang files and `fabric.mod.json` are shared.

## TerraBlender (Fabric: `1.21.1-4.1.0.8`, `26.2-26.2.0.0.2`)

| | 1.21.1 | 26.2 |
|---|---|---|
| `Region(name, RegionType, weight)` | `ResourceLocation` name | `Identifier` name |
| `addBiome`, `addModifiedVanillaOverworldBiomes`, `replaceBiome` | same | same |
| `EndBiomeRegistry.register*Biome(key, int)` | same | same |
| `SurfaceRuleManager.addSurfaceRules` | takes a `RuleSource` | takes a `RuleBuilder` (`HolderGetter<Biome> -> RuleSource`) |
| Entrypoint | `"terrablender"` → `TerraBlenderApi#onTerraBlenderInitialized` | same |

**TerraBlender replaces Fabric's End hook.** TerraBlender's `TheEndBiomeSource` mixin cancels `getNoiseBiome` at HEAD. Once TerraBlender is installed, biomes added through Fabric `TheEndBiomes` are listed as possible but never generate. The self-check caught this. End biomes must go through `EndBiomeRegistry`.

**The test server needs a mixin.** The vanilla gametest server always builds a superflat world, whose Overworld can only produce plains. The dev-only mixin in `common/src/gametest` switches it to the normal preset so Overworld and Nether placement can be tested.
