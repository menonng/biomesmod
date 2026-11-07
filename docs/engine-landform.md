# Landform engine: `biomesmod:landform` and `biomesmod:umbrella_tree`

`biomesmod:landform` places standing landforms on a seed-deterministic jittered grid. Each chunk works out which instances overlap it and writes only its own part, so landforms of any size come out seamless. Run it once per chunk (`"placement": []`). Use step `local_modifications`, which runs after `terrain_shaper` (`raw_generation`) and before trees.

## Settings shared by every shape

```jsonc
{
  "type": "biomesmod:landform",
  "config": {
    "shape": "butte",
    "spacing": 128,
    "chance": 0.5,
    "jitter": 0.75,
    "salt": 0,
    "biomes": ["biomesmod:my_biome"],        // empty = everywhere
    "require_inside": true,                    // the whole footprint must lie in the biome
    "palette": {"strata": [...]},              // StrataPalette
    "talus_blocks": [...],
    "surface_blocks": [...],
    "cap": {"block": {...}, "under": {...}, "depth": 1, "feature": "minecraft:pine_checked", "chance": 0.05},
    "base_y_range": [1, 400],                  // allowed centre floor, relative to sea level
    "max_relief": 24,                          // max floor difference over the footprint
    "rise_from": "ground",                     // or "sea_level": heights count from the sea surface
    "min_distance": 0                          // > 0: at most one instance per min_distance super-cell (unique landmarks)
  }
}
```

### How instances are chosen

Mod biome patches are often only 50-250 blocks across. Instances are therefore picked on a **dense** grid (`spacing` about 0.6-1x the landform's reach) and thinned afterwards:

* **Multiple tries.** Each cell tries up to 6 positions (deterministic from the cell seed) and keeps the first one that passes the height range, biome, shape and footprint checks.
* **Footprint check.** 16 points at 0.65x the reach and 8 at 0.4x must lie in the biome. The outer part is low talus or skirt, which may run slightly past the edge.
* **Separation** (`min_distance` 0). A candidate is dropped if a neighbouring candidate with a higher hash priority stands closer than 0.75x their combined reach.
* **Unique landmarks** (`min_distance` > 0). The world is cut into super-cells of `min_distance`. Each keeps only its highest-priority candidate whose centre lies in the cell's central 70%, so winners are at least 0.3x `min_distance` apart, and chained rejections cannot empty a region. Examples: Devils Tower 1000, Alaid 900, calderas 700.

All of this is a pure function of the seed and the cells, with caches. `SelfCheck` logs a per-biome breakdown of why candidates were rejected (`LandformFeature.diagnose`).

Ranges such as `[8, 20]` are drawn separately for each instance, so no two look alike.

## Shapes

| Shape | For | Key parameters |
|---|---|---|
| `pillar_forest` | Zhangjiajie; Ha Long with `rise_from: sea_level`, `dome`, `notch` | `cluster_radius`, `pillar_spacing`, `density`, `pillar_radius`, `height`, `taper`, `flute`, `dome`, `notch`, `talus` |
| `butte` | Monument Valley, Wadi Rum | `radius`, `elongation`, `height`, `thumbs` (mitten spires), `dome`, `clefts`, `roughness`, `cliff`, pedestal palette |
| `monolith` | Devils Tower; Tre Cime, Lofoten, Monjes with `count` 2-5 and `taper` ~0.8 | `count`, `spread`, `radius`, `height`, `taper`, `flute`, `columns`, `talus` |
| `cone` | Alaid (from the sea), Puna volcanoes | `radius`, `height`, `concavity`, `crater_radius`, `crater_depth`, `crater` `dry`/`lava`/`lake`, `gullies`, `snowline` |
| `caldera` | Ushishir (`floor: sea`, breached), Krenitsyn (`lake` + `cone`, an island with `rise_from: sea_level`), Tengger (`sand` + `cone`) | `radius`, `rim_height`, `flank`, `floor`, `floor_depth`, `cone`, `cone_radius`, `cone_height`, `breach_width`, `floor_block`. The outer flank always runs down to the real floor, so a caldera island slopes on to the sea bed. |
| `sea_stack` | Moher, Lofoten, eroded cliffs | `cluster_radius`, `count`, `stack_radius`, `height`, `arch_chance`, `notch`. Only placed in the shallows. |
| `column_field` | Giant's Causeway | `cell`, `length`, `width`, `rise`, `jitter`. Runs downhill into the sea. |
| `hot_spring` | Grand Prismatic; Dallol with `pools` > 1 | `pools`, `pool_radius`, `band_width`, `spread`, `depth`, `bands` (inner to outer), `chimneys` |
| `cascade` | Iguazu, Devil's Throat | `radius`, `drop`, `islets`, `river_width`, `arc`. Real falls: the upper-river source blocks spill over the lip (ticks are scheduled). |

`biomesmod:umbrella_tree` is a normal feature, so it takes vanilla placement modifiers (count, in_square, heightmap, biome). Its parameters are `trunk`, `leaves`, `trunk_height`, `crown_radius`, `crown_thickness`, `branches`, and `twist` (leaning flat-topped savanna trees).

## Naturalness built in

* Every outline (rims, footprints, skirts, pools) is distorted by angular and 2D noise, so nothing is a circle.
* Walls are cut by `erodedTop`: horizontal bands make ledges and notches, and since nothing overhangs, there are no floating fragments.
* Volcanic flanks use a noisy mix of loose material instead of horizontal strata, which would draw concentric rings on a smooth cone.
* Basins seal their walls automatically. Cascades and breached calderas opt out of sealing exactly where water must flow.

## Verify

Run `./gradlew :26.2:runSelfCheck -Pshowcase=landform -Prender=true [-Ponly=cone_sea,cascade]`.
