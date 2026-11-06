# Architecture and team contract

This is the shared contract for everyone working on this repo, people and subagents alike. When this file and a brief disagree, this file wins. To change it, ask the coordinator.

## Goal

A Fabric mod with real-place-inspired biomes for Minecraft **1.21.1** and **26.2** (Mojmap). The biomes reproduce the *feel* of each place, not an exact copy: palette, vegetation, landforms, colours and climate slot. Any technique is allowed, including custom features, new blocks and density-function overrides. Every technique must work in **both** versions and pass verification.

## Phases and roles (`.claude/agents/*.md`)

| Phase | Role (agent) | Owns (may create/edit) |
|---|---|---|
| A | `place-researcher` | `docs/places.md`, `specs/places.json` |
| A | `biome-data-engineer` | `tools/biomegen/**`, `specs/schema/**`, `specs/regions.json`, `docs/biome-specs.md`, `common/.../ModBiomes.java`, `common/.../worldgen/**`, `versions/*/.../compat/SurfaceRulesCompat.java`, `versions/*/.../compat/Biomegen*.java`, all generated resources (see below), `common/.../SelfCheck.java` (biome list source only) |
| A | `terrain-field-engineer` | `common/.../terrain/field/**`, `versions/*/.../compat/Field*.java`, `common/src/gametest/.../showcase/FieldShowcase.java`, `docs/engine-terrain.md`, `specs/features/field_*.json` (examples) |
| A | `landform-engineer` | `common/.../terrain/landform/**`, `versions/*/.../compat/Landform*.java`, `common/src/gametest/.../showcase/LandformShowcase.java`, `docs/engine-landform.md`, `specs/features/landform_*.json` (examples) |
| A→B | `coastal-terrain-engineer` | `common/.../terrain/coast/**`, `versions/*/.../compat/Coast*.java`, `common/src/gametest/.../showcase/CoastShowcase.java`, `common/src/main/resources/data/minecraft/worldgen/**` (density-function overrides), `docs/engine-coast.md`, `specs/biomes/coast_*.json`, `specs/features/coast_*.json` |
| B | `biome-author` (one per place group) | `specs/biomes/<assigned ids>.json`, `specs/features/<assigned id>_*.json` |
| C | `worldgen-verifier` | `docs/verification/**` (reports only) |
| all | coordinator (main session) | everything else: build files, `BiomesMod.java`, `terrain/ModFeatures.java`, `terrain/util/**`, `debug/**`, `docs/architecture.md`, merges |

**Never edit a file outside your column.** If you need a change there, describe it in your final report. `common/...` means `common/src/main/java/io/github/menonng/biomesmod/...`.

## Repo layout

```
common/src/main/java/.../            shared code (compiled into every version)
  BiomesMod, ModBiomes, SelfCheck
  worldgen/        TerraBlender regions, surface rules (biome-data-engineer)
  terrain/ModFeatures.java           calls TerrainFeatures / LandformFeatures / CoastFeatures .register()
  terrain/util/    WorldSampler (sample biome/height anywhere), StrataPalette (shared palette codec)
  terrain/field/   column terrain shaping features
  terrain/landform/ grid-placed landforms + custom trees
  terrain/coast/   coast, island, polder features
  debug/DebugRender   PNG renderer for verification
common/src/main/resources/            shared data (features that are identical across versions, lang)
common/src/gametest/                  dev-only: preset mixin, Showcases runner + per-engine showcase classes
versions/<mc>/src/main/java/.../compat/   version shims (ONLY place allowed to touch version-specific APIs)
versions/<mc>/src/main/resources/data/biomesmod/worldgen/biome/   generated per-version biome JSON
specs/                                version-agnostic source of truth for biomes (generator input)
tools/biomegen/                       Python generator: specs -> per-version JSON
```

## Version rules

* `docs/api-differences.md` lists the known differences. Common code must compile against **both** versions. If an API differs, add a shim in `versions/1.21.1/.../compat/` **and** `versions/26.2/.../compat/` with the same class and method signatures. Name it after your role (e.g. `FieldCompat`).
* Look up real APIs, never guess. Decompiled Mojmap sources are in `C:\Users\user\AppData\Roaming\minecraft-dev-mcp\decompiled\{1.21.1,26.2}\mojmap\` (Java under `net/`, vanilla data under `data/minecraft/`). The `minecraft-dev-cli` tool is also available after running `fnm env | Out-String | Invoke-Expression` in PowerShell.
* Known traps:
  * `ResourceLocation` vs `Identifier`: use `compat/Ids`.
  * `getMinBuildHeight` vs `getMinY`: use `compat/LevelCompat`.
  * `SurfaceRules.isBiome` takes a `HolderGetter` in 26.2.
  * Biome visual fields moved to `attributes` in 26.2.
  * Colours are ints in 1.21.1 and `"#rrggbb"` strings in 26.2.
  * `carvers` is `{}` in 1.21.1 and `[]` in 26.2.
  * Feature lists differ per version.

## Worldgen rules (engine features)

1. **Write only inside the chunk being decorated.** A placed feature runs once per chunk with `origin` = the chunk's minimum corner (no placement modifiers). Modify only blocks in `[chunkMinX, chunkMinX+15] x [chunkMinZ, chunkMinZ+15]`.
2. **Sample anywhere.** Use `terrain/util/WorldSampler` for biome and base terrain height at any x/z, including ungenerated chunks. Every decision that spans chunks (where a pillar stands, its radius, whether it fits in the biome) must be a pure function of world seed + coordinates, so neighbouring chunks agree. Seed your own RNG from `WorldSampler.seed()` and cell coordinates, never from the context random.
3. **Gate by biome yourself.** Do not use the `minecraft:biome` placement filter for chunk-wide features. Check the biome per column or per landform instance. Blend to zero towards biome edges, or keep instances fully inside the biome, so nothing is cut off at a chunk where the feature did not run.
4. **Keep it fast.** Cache per-chunk samples. A chunk should take a few ms. Never force-load chunks from a feature.
5. **Configs are Codec-based JSON.** Every behaviour is a `mode`/`shape` value plus parameters, so biome authors only write data. Biome gating is a config field (`"biomes": ["biomesmod:x", ...]`). An empty list means "everywhere", which showcases use.
6. **Document every mode/shape** in your `docs/engine-*.md` with a copy-pasteable JSON example and a render.

### Naturalness rules (user requirement: avoid unnatural terrain as much as possible)

The user rejected the crescent-shaped, water-filled gorges seen in early renders (cause: vanilla `minecraft:canyon` ravines breaching the surface near sea level and flooding; a global override in `data/minecraft/worldgen/configured_carver/canyon.json` keeps ravines underground). Treat these as defects everywhere:

* No crescent/arc gashes or thin water-filled slots. Do not reintroduce surface-breaching carvers.
* No geometric tells: perfect circles or rings, straight edges, 16-block chunk lines, stair-step cones, constant-width bands. Displace outlines with multi-octave noise, vary sizes, taper and blend.
* Water only in plausible basins: shores, connected channels, no one-block water lines, no water hanging on slopes, no dry holes punched into the sea.
* Landforms have context: talus at bases, eroded tops, plausible vegetation, transitions at biome edges.
* Compare renders side by side with photos of the real place.

### Feature steps (`GenerationStep.Decoration` order)

`raw_generation`, `lakes`, `local_modifications`, `underground_structures`, `surface_structures`, `strongholds`, `underground_ores`, `underground_decoration`, `fluid_springs`, `vegetal_decoration`, `top_layer_modification`. Terrain-reshaping features go in `raw_generation` (before lakes, trees and snow). Surface dressing goes in `local_modifications`.

## Spec format (generator input, owned by biome-data-engineer)

`specs/biomes/<id>.json`, version-agnostic. The generator is the only thing that writes biome JSON.

```jsonc
{
  "id": "salar_de_uyuni",
  "names": { "en_us": "Salar de Uyuni", "ko_kr": "우유니 소금사막" },
  "places": [4],                                  // indices into specs/places.json
  "dimension": "overworld",                       // overworld | nether | end
  "placement": { "replace": ["minecraft:desert"] },  // overworld: vanilla climate slot(s) taken inside mod regions
  //            { "nether_point": {"temperature": 0.75, "humidity": -0.35, "offset": 0.0} }
  //            { "end": "highlands|midlands|edge|islands", "weight": 5 }
  "base": "minecraft:desert",                     // vanilla biome to inherit carvers/features/spawns/sounds from (per version)
  "climate": { "temperature": 0.3, "downfall": 0.0, "has_precipitation": false },
  "colors": { "sky": "#…", "fog": "#…", "water": "#…", "water_fog": "#…", "grass": "#…", "foliage": "#…" },  // all optional
  "particles": [ { "type": "minecraft:white_ash", "probability": 0.01 } ],  // simple particle types only
  "music": "minecraft:music.overworld.desert",    // optional
  "features": {
    "remove": ["minecraft:patch_dead_bush"],                       // vanilla placed features dropped from base
    "add": { "vegetal_decoration": ["minecraft:trees_savanna"] },  // vanilla placed features added
    "mod": { "raw_generation": ["biomesmod:uyuni_flat"] }          // ids of specs/features/*.json
  },
  "spawners": "base",                             // or a full vanilla spawners object
  "surface": { "top": "minecraft:white_concrete_powder", "under": "minecraft:calcite", "under_depth": 3, "underwater": "minecraft:sand" }  // optional
}
```

`specs/features/<id>.json` (placed feature `biomesmod:<id>`):

```jsonc
{
  "configured": { "type": "biomesmod:landform", "config": { … } },  // any feature type
  "configured_by_version": { "1.21.1": { … }, "26.2": { … } },      // optional: when vanilla config formats differ
  "placement": []                                                   // [] = once per chunk at chunk origin (engine features)
}
```

Generated output, committed but **never hand-edited**:

* `versions/<mc>/src/main/resources/data/biomesmod/worldgen/biome/*.json`
* configured/placed features (`common/` when identical, else per version)
* `common/src/main/resources/biomesmod/placements.json`, read by Java at startup
* lang files

## Verification (everyone)

PowerShell or Git Bash, from the repo (or worktree) root:

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-26.0.2.1"
./gradlew build                                       # both versions
./gradlew :1.21.1:runSelfCheck :26.2:runSelfCheck -Prender=true   # every biome found + PNGs
./gradlew :26.2:runSelfCheck -Pshowcase=landform -Prender=true   # your engine showcase
```

* Renders land in `versions/<mc>/run/selfcheck/renders/` as `<name>.png` (top-down) and `<name>_section.png` (west-east cut). Open them with the Read tool and look at them. A change is not done until you have seen it render correctly in **both** versions.
* For Nether terrain use the `_section` image; the top-down view only shows the ceiling.
* Self-check worlds are fresh every run.

## Git

* Phase A/B agents run in their own git worktree. Commit your work on your worktree branch with a clear message ending in `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Never push. Never touch `servers/` (local Paper servers).
* The coordinator merges branches, regenerates outputs and pushes.
