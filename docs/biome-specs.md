# Biome specs and the generator

Owner: `biome-data-engineer`. This file is the reference for `specs/` and `tools/biomegen/`. The short schema in `docs/architecture.md` ("Spec format") is the contract; everything below is either that schema in detail or a backwards-compatible extension (marked **ext**).

```
specs/biomes/<id>.json ─┐
specs/features/<id>.json ├─ python -I tools/biomegen/generate.py ─┬─ versions/<mc>/.../data/biomesmod/worldgen/biome/<id>.json
specs/regions.json ─────┘   (reads real vanilla data per version)  ├─ .../data/biomesmod/worldgen/{configured,placed}_feature/<id>.json
                                                                   ├─ common/src/main/resources/biomesmod/placements.json  -> Kotlin (TerraBlender)
                                                                   └─ common/src/main/resources/assets/biomesmod/lang/<locale>.json
```

## Add a biome in 5 steps

1. Copy `specs/biomes/gotjawal.json` (overworld), `danakil_flats.json` (nether) or `chorus_thicket.json` (End) to `specs/biomes/<id>.json`. Set `"id"` to the file name, the names, the base biome and the placement.
2. Put each custom feature in `specs/features/<id>_<what>.json` and list it under `features.mod.<step>` as `"biomesmod:<id>_<what>"`.
3. Run `python -I tools/biomegen/generate.py` from the repo root. Fix every `ERROR:` line it prints. Read the warnings too.
4. Run `./gradlew build` and `./gradlew :1.21.1:runSelfCheck :26.2:runSelfCheck -Prender=true` (JAVA_HOME=`C:\Program Files\Java\jdk-26.0.2.1`). The self-check finds every biome listed in `placements.json` automatically.
5. Open `versions/<mc>/run/selfcheck/renders/<id>.png` and `<id>_section.png` in both versions. Check them against the naturalness rules in `docs/architecture.md`. Commit the spec and the generated files together.

## Running the generator

```
python -I tools/biomegen/generate.py            # regenerate: writes changed files, deletes stale generated files
python -I tools/biomegen/generate.py --check    # writes nothing; exit 1 if any generated file is stale or extra
python -I tools/biomegen/generate.py --quiet    # only warnings, errors and a one-line summary
```

* Python 3, stdlib only. It works from any directory and finds the repo from its own path. Files are read and written as UTF-8 with LF line endings.
* Vanilla data comes from `%APPDATA%\minecraft-dev-mcp\decompiled\<mc>\mojmap\`. Override it with `--vanilla <dir>` or `BIOMEGEN_VANILLA`. Versions come from `versions/*/gradle.properties`.
* The generator reads the real vanilla data for each version: biome JSON, placed and configured features, carvers and noises. It parses the decompiled sources for the overworld climate slots, the nether and End biome lists, simple particle types and sound events. Block ids come from the vanilla blockstate assets and entity ids from the vanilla lang file.
* Output is deterministic: keys are sorted, mod-biome lists are sorted by id, and placement data is built in a fixed order. Running it twice changes nothing.
* With any error it writes nothing and exits 1.

### Files the generator owns

The generator owns every `.json` in these directories. In write mode it deletes any file there that no spec produces, and `--check` reports such files as extra.

* `versions/<mc>/src/main/resources/data/biomesmod/worldgen/biome/`
* `common/` and `versions/<mc>/` `src/main/resources/data/biomesmod/worldgen/configured_feature/` and `placed_feature/`
* `common/src/main/resources/assets/biomesmod/lang/`
* `common/src/main/resources/biomesmod/placements.json`

Never hand-edit these files. To add a hand-made feature, write a feature spec for it.

## Biome spec: `specs/biomes/<id>.json`

JSON. `//` and `/* */` comments are allowed (**ext**), as are keys starting with `_` and `"$schema"` (editor hints: `specs/schema/biome.schema.json`). Any other unknown key is an error.

| Key | Required | Meaning |
|---|---|---|
| `id` | yes | Must equal the file name. `[a-z0-9_]+`. The biome becomes `biomesmod:<id>`. |
| `names` | yes | `{ "en_us": "...", "ko_kr": "..." }`. Both are required. Other locales are allowed and get their own lang file. |
| `places` | no | Indices (`n`) into `specs/places.json`. Unknown values give a warning. Default `[]`. |
| `dimension` | yes | `overworld`, `nether` or `end`. |
| `placement` | yes | See [Placement](#placement). |
| `base` | yes | Vanilla biome to inherit from, per version: climate, colours, sounds, music, particles, carvers, features, spawners and spawn costs. Must exist in every version. |
| `climate` | no | `temperature`, `downfall`, `has_precipitation`, and `temperature_modifier` (`none`/`frozen`, **ext**). Fields you leave out come from `base`. |
| `colors` | no | `sky`, `fog`, `water`, `water_fog`, `grass`, `foliage`, all `"#rrggbb"`. **ext**: `dry_foliage` (26.2 only, ignored in 1.21.1) and `grass_modifier` (`none`/`dark_forest`/`swamp`). Fields you leave out come from `base`. |
| `particles` | no | `[{ "type": "minecraft:white_ash", "probability": 0.06 }]`. Simple particle types only. `[]` removes the base particles. 1.21.1 keeps only the first entry. |
| `music` | no | A sound event id such as `"minecraft:music.overworld.desert"`. **ext**: `{ "sound", "min_delay", "max_delay", "replace_current_music" }` (defaults 12000 / 24000 / false). |
| `features` | no | See [Features](#features). |
| `spawners` | no | `"base"` (default) or a full vanilla spawners object. Missing categories become `[]`. Entries are `{ "type", "weight", "minCount", "maxCount" }`. |
| `spawn_costs` | no, **ext** | `"base"` (default) or a vanilla `spawn_costs` object. |
| `creature_spawn_probability` | no, **ext** | Number in `[0, 1)`. Defaults to `base`. |
| `surface` | no | See [Surface](#surface). Overworld and nether only. |
| `carvers` | no | See [Carvers](#carvers). |

### How it maps to each version

| Spec | 1.21.1 | 26.2 |
|---|---|---|
| `colors.sky` / `fog` / `water_fog` | `effects.sky_color` / `fog_color` / `water_fog_color` (int) | `attributes["minecraft:visual/sky_color" / "fog_color" / "water_fog_color"]` (`"#rrggbb"`) |
| `colors.water` / `grass` / `foliage` | `effects.*_color` (int) | `effects.*_color` (`"#rrggbb"`) |
| `particles` | `effects.particle` `{options:{type}, probability}` (first entry only) | `attributes["minecraft:visual/ambient_particles"]` (all entries) |
| `music` | `effects.music` | `attributes["minecraft:audio/background_music"].default`; the base `creative`/`underwater` entries are kept |
| `carvers` | `{ "air": [...] }`, or `{}` when empty | `[...]` |

Everything else, including fog in 26.2 when no `fog` is given, comes from the base biome of that version. A 26.2 overworld base usually has no `fog_color`, because that comes from the dimension.

### Placement

* **Overworld, site:** `{ "site": { ... } }` places the biome as a large site at its real elevation; see [engine-sites.md](engine-sites.md) for every field. All real-place biomes use this.
* **Overworld, slots:** `{ "replace": ["minecraft:desert", ...] }` lists the vanilla climate slots this biome takes inside a mod region. Every slot must exist in the overworld biome builder of **every** version, so `minecraft:pale_garden` and `minecraft:sulfur_caves` (26.2 only) are rejected. A slot carries its terrain shape with it: a `stony_peaks` slot gives peaks, `beach` gives the coast strip. Choose slots for the terrain you want.
* **Nether:** `{ "nether_point": { "temperature": 0.75, "humidity": -0.35, "offset": 0.0 } }`. **ext**: `continentalness`, `erosion`, `depth`, `weirdness` (default 0). All in `[-2, 2]`. A point may not equal a vanilla nether point or another mod point.
* **End:** `{ "end": "highlands" | "midlands" | "edge" | "islands", "weight": 5 }`. Vanilla End biomes have weight 10 each in TerraBlender's config.

### Region packing (overworld)

The generator puts every slot-placed overworld biome into exactly one TerraBlender region, named `biomesmod:overworld_<n>`:

1. It sorts the biomes by number of slots (most first), then by id.
2. First fit: each biome goes into the first region where none of its slots is taken yet. If there is none, a new region opens. Within one region a vanilla biome is replaced by at most one mod biome.
3. Each region is the full vanilla overworld layout (`addModifiedVanillaOverworldBiomes`) with its slots swapped.

So the number of regions equals the largest number of biomes that share a slot, or more when multi-slot biomes collide. Each region has the weight from `specs/regions.json`:

```json
{ "overworld": { "default_weight": 3, "weights": { "2": 5 } }, "nether": { "default_weight": 2 } }
```

`weights` overrides single regions by their 1-based number. The vanilla region weighs 10, so with N mod regions of weight 3 one region covers 3/(10+3N) of the world. A mod biome is about as common as its vanilla slot in that share. Every mod biome sits in one region, so with equal weights all mod biomes are equally common relative to their slots. All nether biomes share one region, `biomesmod:nether_1`, which also contains the vanilla nether points (read from the game at runtime). The generator prints the packing on every run.

### Features

```jsonc
"features": {
  "clear":  ["vegetal_decoration"],                        // ext: drop all base vanilla features of these steps
  "remove": ["minecraft:patch_dead_bush"],                 // vanilla placed features dropped from base (all steps)
  "add":    { "vegetal_decoration": ["minecraft:trees_savanna"] },   // vanilla placed features added
  "mod":    { "raw_generation": ["biomesmod:uyuni_flat"] },          // specs/features/<id>.json, "biomesmod:" optional
  "by_version": { "26.2": { "add": { "vegetal_decoration": ["minecraft:patch_dry_grass_desert"] } } }   // ext
}
```

* Steps: `raw_generation`, `lakes`, `local_modifications`, `underground_structures`, `surface_structures`, `strongholds`, `underground_ores`, `underground_decoration`, `fluid_springs`, `vegetal_decoration`, `top_layer_modification`.
* `add` ids must exist in every version. For a feature that exists in one version only, use `by_version.<mc>`, which takes the same `clear`/`remove`/`add`/`mod` keys and applies them to that version only.
* `remove` ids must exist in at least one version. They are dropped wherever present, so version-specific names can share one list. Example: gotjawal removes `trees_birch_and_oak` (1.21.1) and `trees_birch_and_oak_leaf_litter` (26.2).
* **Order is not yours to choose.** Per version and per step, the generator computes one canonical order: a topological merge of every vanilla biome's list. Every biome lists its vanilla features in that order, followed by its mod features in a single global mod order. The global mod order is a topological merge of all specs' `mod` lists. If two biomes list shared mod features in opposite orders, that is an error.
* A vanilla feature added to a step where no vanilla biome uses it goes after the canonical features of that step, and you get a warning.
* **Self-test** (every run, both versions): the generator rebuilds Minecraft's `FeatureSorter` graph from all vanilla biomes plus all generated biomes. It fails on any cycle ("Feature order cycle" would crash the game at startup), on any pair of features ordered both ways, and on mod placed features that are identical to another placed feature (Minecraft would treat them as one node).

### Biome tags and vanilla structures

The generator adds every mod biome to the vanilla biome tags that list its `base` biome directly (`data/minecraft/tags/worldgen/biome/**`), so it gets what the base gets: strongholds, mineshafts, trial chambers, ruined portals, villages, mob variants. `"structures"` in the biome spec: `"base"` keeps every `has_structure/*` tag of the base, `"none"` drops them, and the default `"auto"` drops them for biomes whose ground a `terrain_shaper` rebuilds after structures are planned, except villages and pillager outposts, which are planned on the shaped ground (see "Villages in sites" in `engine-sites.md`). Uninhabited places (Antarctica, salt pans, the great dune seas) use `"none"`. The self-check logs the structure starts around each biome.

### Surface

```json
"surface": { "top": "minecraft:white_concrete_powder", "under": "minecraft:calcite", "under_depth": 3,
             "underwater": "minecraft:sand", "top_patches": [ { "block": "minecraft:sand", "noise": "minecraft:surface", "min": 0.8 } ] }
```

The surface becomes TerraBlender surface rules, built in Kotlin from `placements.json` (`worldgen/ModSurfaceRules`; the version split lives in `compat/SurfaceRulesCompat`). Inside this mod's regions they run before the vanilla rules. Anything they do not set falls through to vanilla. For one biome, the first match wins:

| Key | Overworld | Nether |
|---|---|---|
| `top_patches` (**ext**) | floor block where the 2D noise (`noise`, default `minecraft:surface`) is in `[min, max]` (`max` optional); dry floors only; checked before `top` | same, on every floor |
| `top` | floor block where the floor is not under water (`waterBlockCheck(-1, 0)`) | floor block (`ON_FLOOR`) |
| `underwater` | floor block under water | not allowed |
| `under` | blocks below the floor: `under_depth` blocks, or the vanilla variable depth (`UNDER_FLOOR`, about 3–6) when `under_depth` is not set. A wet floor also gets `under` when `underwater` is not set. | same |

Overworld rules apply only near the surface (`abovePreliminarySurface`), so cave floors keep the vanilla blocks. Nether rules apply on every floor, like vanilla nether biomes. Use `top_patches` to break a uniform top into natural patches rather than leaving a constant-width band. Blocks are plain block ids in their default state. Vanilla ids are checked against both versions. `biomesmod:` blocks are only checked for a blockstate file (a warning), and in 1.21.1 they must be registered before TerraBlender initialises.

### Carvers

`"carvers": "base"` (default) | `"underground"` (**ext**) | `["minecraft:cave", ...]`

* `base` keeps the base biome's carvers.
* `underground` keeps the base carvers minus every carver that can breach the surface. Use it for biomes whose surface must stay intact, such as salt pans, dunes, terraces and below-sea-level land.
* A list replaces the carvers. A listed carver that is not among the base biome's carvers and can breach the surface gives a warning (naturalness rules: do not reintroduce surface-breaching carvers).

**Limit of per-biome carvers.** Minecraft picks the carvers of a chunk from the biome of the chunk where the carver *starts*, sampled at y = 0 (so a cave biome under the surface counts), and one carver reaches up to 8 chunks. A ravine or cave that starts in a neighbouring biome can therefore still cut into yours, and none of these settings can stop that. Only the global carver overrides in `data/minecraft/worldgen/configured_carver/` stop it everywhere. The early self-check renders show this: a dry vanilla ravine crosses the plain `minecraft:forest` beside gotjawal.

Breach test: the top of the carver's `y` range, plus its largest vertical radius, reaches the surface line. The surface line is sea level 63 in the overworld and the lava sea at 32 in the nether. The vertical radius is `(1.5 + thickness max) * yScale * vertical factor` for canyons, and the larger of a room `(1.5 + 7) * yScale` and a tunnel `(1.5 + 3) * vertical_radius_multiplier` for caves. Carver configs are read from this repo's overrides first (`versions/<mc>/` then `common/` `src/main/resources/data/<ns>/worldgen/configured_carver/`), then vanilla. With vanilla data: `minecraft:cave` (y up to 180) and `minecraft:canyon` (y up to 67) breach the surface; `minecraft:cave_extra_underground` (y up to 47) does not; `minecraft:nether_cave` breaches the nether floor. The coastal engineer's canyon override keeps ravines underground everywhere. `underground` is for biomes that must also be free of ordinary cave entrances.

## Feature spec: `specs/features/<id>.json`

The file name is the id. It becomes placed feature `biomesmod:<id>` and, when it defines one, configured feature `biomesmod:<id>`. Name it `<biome id>_<what>` (biome authors) or `<engine>_<what>` (engine examples).

```jsonc
{
  "configured": { "type": "biomesmod:landform", "config": { } },   // any feature type
  "configured_by_version": { "1.21.1": { }, "26.2": { } },         // optional per-version override of "configured"
  "feature": "minecraft:chorus_plant",                              // ext: reference an existing configured feature instead
  "placement": [],                                                  // [] = once per chunk at the chunk origin (engine features)
  "placement_by_version": { "26.2": [ ] }                           // ext: per-version placement
}
```

* Use either `configured`/`configured_by_version` or `feature`, not both. `feature` may name a vanilla configured feature (checked per version) or another feature spec's `biomesmod:<id>`.
* The output goes to `common/src/main/resources/data/biomesmod/worldgen/<kind>/` when it is identical in all versions, otherwise to `versions/<mc>/src/main/resources/...`. Configured and placed files are decided separately.
* Only the feature type and the placement modifier shape are checked. Engine configs are not validated, so check them in the self-check renders.
* A feature spec that no biome uses is still generated, with a warning.

## Validation (errors unless noted)

* Bad JSON, unknown keys (with a "did you mean" hint), an `id` that does not match the file name, missing `en_us`/`ko_kr` names.
* Unknown vanilla biomes, placed or configured features, carvers, blocks, noises, particles, sound events or entities **for each version**.
* A missing mod feature (`features.mod` without a `specs/features/<id>.json`).
* Bad colours (not `#rrggbb`).
* Duplicate ids. The id is the file name, so a duplicate shows up as an id/file-name mismatch. A slot listed twice is also an error.
* An overworld slot that does not exist in every version, a nether point equal to a vanilla or mod point, an unknown End type.
* Feature order conflicts or cycles (the self-test above) and identical placed features.
* Warnings: `remove` entries absent from the base, vanilla features added to an unusual step, more than one particle (1.21.1), surface-breaching carvers added by an explicit list, a `base` from another dimension, unused feature specs, unknown `places`.

## Feature order and other biome mods

Minecraft requires one consistent feature order per step across *every* biome of a dimension, including other mods' biomes. When two biomes list the same two features in opposite orders, the game stops with "Feature order cycle found" on the first chunk. The generator sorts every step topologically after the vanilla biomes; pairs that no vanilla biome orders follow `tools/biomegen/order_hints.json`, which records the order other worldgen mods use. To check a mod, run

```bash
python -I tools/biomegen/ordercheck.py versions/<mc>/src/main/resources/data/biomesmod/worldgen/biome <mod.jar or data pack.zip>...
```

It lists every inverted pair; add each as a hint (in the order the other mod uses) and regenerate. Checked against Oh The Biomes We've Gone, Regions Unexplored, Terralith, WWOO, Cascades, JJThunder To The Max, Montane, Streams Reflowing and Tectonic.

## Runtime (Kotlin)

* `worldgen/Placements` reads `/biomesmod/placements.json` from the mod jar on first use (Gson).
* `ModBiomes.ALL` is built from its `biomes` list. `SelfCheck` iterates `ModBiomes.ALL`, so every spec is checked automatically.
* `worldgen/TerraBlenderEntry` registers one `ModOverworldRegion` per packed region (`replaceBiome` for each slot) and one `ModNetherRegion` (vanilla nether points plus mod points). It also registers the End biomes through `EndBiomeRegistry` and the surface rules through `compat/SurfaceRulesCompat`.
* Version shims: `compat/BiomegenCompat.id(String)` parses ids (`ResourceLocation`/`Identifier`). `compat/SurfaceRulesCompat` covers `isBiome` with or without a `HolderGetter`, `noiseCondition` versus `noiseCondition2d`, and a `RuleSource` versus a `RuleBuilder`.

## Lang

`assets/biomesmod/lang/<locale>.json` gets `biome.biomesmod.<id>` from each spec's `names`. **ext**: extra keys in `specs/lang/<locale>.json` (for example block names from engine work) are merged in. A key that collides with a generated one is an error.
