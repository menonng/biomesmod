# Landform engine: `biomesmod:landform` and the custom trees and decorations

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
    "min_distance": 0                          // > 0: at most one instance per min_distance super-cell (unique landmarks); -1: one per site, near its centre
  }
}
```

### How instances are chosen

Mod biome patches are often only 50-250 blocks across. Instances are therefore picked on a **dense** grid (`spacing` about 0.6-1x the landform's reach) and thinned afterwards:

* **Multiple tries.** Each cell tries up to 6 positions (deterministic from the cell seed) and keeps the first one that passes the height range, biome, shape and footprint checks.
* **Footprint check.** 16 points at 0.65x the reach and 8 at 0.4x must lie in the biome. The outer part is low talus or skirt, which may run slightly past the edge.
* **Separation** (`min_distance` 0). A candidate is dropped if a neighbouring candidate with a higher hash priority stands closer than 0.75x their combined reach.
* **Unique landmarks** (`min_distance` > 0). The world is cut into super-cells of `min_distance`. Each keeps only its highest-priority candidate whose centre lies in the cell's central 70%, so winners are at least 0.3x `min_distance` apart, and chained rejections cannot empty a region. Examples: Devils Tower 1000, Alaid 900, calderas 700.
* **One per site** (`min_distance` -1). For site-placed biomes: each site of the biome gets exactly one instance, at its centre or (if the terrain does not suit the shape there) at up to 9 nearby positions within 0.35x the site's core radius. Devils Tower, Alaid, the calderas, Tre Cime, Iguazu, Grand Prismatic and Moraine Lake's lake use it.

All of this is a pure function of the seed and the cells, with caches. `SelfCheck` logs a per-biome breakdown of why candidates were rejected (`LandformFeature.diagnose`).

Ranges such as `[8, 20]` are drawn separately for each instance, so no two look alike.

## Shapes

| Shape | For | Key parameters |
|---|---|---|
| `pillar_forest` | Zhangjiajie; Ha Long with `rise_from: sea_level`, `dome`, `notch`; Salar de Tara crags, Socotra granite crags | `cluster_radius`, `pillar_spacing`, `density`, `pillar_radius`, `height`, `taper`, `flute`, `dome`, `notch`, `talus`. True 3D bodies: `lobes` (1-4 overlapping lobes that merge low and part high), `joints` (polygonal jointed outline), `ledges` (bedding layers of different width with undercut notches and lips), `lean`; dressing: `ledge_block`, `streaks` + `streak_density` (dark weathering streaks), `vines`, `ledge_feature` + `ledge_chance` (shrubs on ledges). |
| `butte` | Monument Valley, Wadi Rum | `radius`, `elongation`, `height`, `thumbs` (mitten spires), `dome`, `clefts`, `roughness`, `cliff`, pedestal palette |
| `monolith` | Devils Tower; Tre Cime, Lofoten, Monjes with `count` 2-5 and `taper` ~0.8 | `count`, `spread`, `radius`, `height`, `taper`, `flute`, `columns`, `talus` |
| `cone` | Alaid (from the sea), Puna volcanoes | `radius`, `height`, `concavity` (profile measured from the crater rim, so there is no chimney), `crater_radius`, `crater_depth`, `crater` `dry`/`lava`/`lake`, `gullies`, `snowline` (snow in the gullies first, bare lava ridges), `parasites` (scoria cones on the flanks), `vents` (fumaroles at the crater), `green` (vegetated lower flanks). The surface is dressed by zone and radial lava tongues, never banded. |
| `caldera` | Ushishir (`floor: sea`, breached), Krenitsyn (`lake` + `cone`, an island with `rise_from: sea_level`), Tengger (`sand` + `cone`) | `radius`, `rim_height`, `flank`, `floor`, `floor_depth`, `cone`, `cone_radius`, `cone_height`, `breach_width`, `floor_block`. The outer flank always runs down to the real floor, so a caldera island slopes on to the sea bed. |
| `sea_stack` | Moher, Lofoten, eroded cliffs | `cluster_radius`, `count`, `stack_radius`, `height`, `arch_chance`, `notch`. Only placed in the shallows. |
| `column_field` | Giant's Causeway | `cell`, `length`, `width`, `rise`, `jitter`. Runs downhill into the sea. |
| `hot_spring` | Grand Prismatic; Dallol with `pools` > 1 | `pools`, `pool_radius`, `band_width`, `spread`, `depth`, `bands` (inner to outer; default geyserite and the thermal mat blocks), `chimneys` |
| `geyser` | Grand Prismatic geyser basin | `cone_radius`, `cone_height`, `apron_radius`, `mats`, `pools`, `fumaroles`, `snags`. A terraced geyserite cone with a live vent: in 26.2 vanilla's geyser (a 4-deep water pipe over potent sulfur on magma, about one eruption a minute up to 24 blocks), in 1.21.1 the mod's `geyser_vent` block (an eruption every 1-3 minutes that throws entities up). Runoff channels coloured by temperature, rimstone pools, steam vents and bleached dead pines. |
| `cascade` | Iguazu, Devil's Throat | `radius`, `drop`, `islets`, `river_width`, `arc`. Real falls: the upper-river source blocks spill over the lip (ticks are scheduled). |
| `glacial_lake` | Moraine Lake | `radius`, `elongation`, `depth`, `pile`. A long lobed cirque lake whose water sits at the lowest point of its rim: on a slope the uphill shore is a cut face rising from the water, the downhill shore gets a moraine dam, and a rockpile (`pile` blocks high) stands at the outlet. Coloured by the biome's water colour. |

`biomesmod:umbrella_tree` is a normal feature, so it takes vanilla placement modifiers (count, in_square, heightmap, biome). Its parameters are `trunk`, `leaves`, `trunk_height`, `crown_radius`, `crown_thickness`, `branches`, and `twist` (leaning flat-topped savanna trees).

## Trees and decorations

| Feature | Used by | Notes |
|---|---|---|
| `biomesmod:umbrella_tree` | Socotra | `forks` > 0 grows the dragon's blood form: a stout (sometimes 2x2, `thick`) trunk forking repeatedly into upturned pairs under one dense umbrella crown; `twist` gives flat-crowned savanna trees. |
| `biomesmod:bottle_tree` | Socotra | Adenium: swollen base, stubby branches, leaf tufts with flowers. `trunk`, `tips`, `flowers`, `height`, `girth`. |
| `biomesmod:giant_tree` | Giant Redwood Forest, White Old Growth | `style` `spire` (redwood: up to 5 blocks thick, 50-75 high, buttress roots, drooping branch sprays, conical top) or `broad` (veteran broadleaf: massive trunk, surface roots, limbs forking into a wide leaf dome, some dead limbs). Logs oriented along each limb. `dress`: `hanging` (silver beard lichen under the leaves), `bracket` (moon bracket fungi on the trunk), `hollow` (a wisp-breathing hollow), `carpet` (moss on the roots). |
| `biomesmod:flower_strips` | Polder Tulip Fields | Dutch bulb fields: rectangular fields of single-colour strips with headlands and fallow fields. Place once per chunk. |
| `biomesmod:windmill` | Polder Tulip Fields | A Dutch smock mill: brick plinth with door, eight-sided tapering timber tower, reefing stage, cap and four lattice sails. |
| `biomesmod:dead_tree` | Sossusvlei (Deadvlei) | A leafless standing skeleton: a short, slightly leaning trunk of `log` forking into 2-4 bare limbs, `height` 3-5; only on the blocks in `ground` (the white clay pans). |
| `biomesmod:cliff_falls` | Geirangerfjord, Lauterbrunnen, Magellanic fjords, Lofoten | Waterfalls found on real walls: a face dropping `min_drop` blocks at a mean gradient of `min_slope` (default 1; it need not be vertical), a stream traced uphill from the lip, the water cut into a one-block notch down the face, a plunge pool at the foot. `cluster` falls side by side, `chance` per chunk, `tries`. |
| `biomesmod:fallen_log` | White Old Growth | An old trunk lying along the ground with a stump, moss carpet on its back and moon brackets on its flanks. |
| `biomesmod:rock_swap` | Star Abyss | Re-dresses one biome's ground where surface rules cannot (the End): `from` blocks become a clustered `to` mix, with `top` patches (stardust) over `top_coverage` of the surface. |
| `biomesmod:floating_isle` | Star Abyss | A small island hanging 12-24 blocks up: lobed inverted cone of rock, stardust top with star blooms and crystal buds, astral roots and ceiling blooms underneath; a star spring on the ground below lifts players up to it. |
| `biomesmod:abyss_well` | Star Abyss | A shaft punched through the island into the void, walls of starlit abyss stone with blooms and hanging roots, crystal buds on the rim. |
| `biomesmod:crystal_cluster` | Star Abyss | Giant six-sided crystal prisms at angles, with a rock collar and buds. |

## Naturalness built in

* Every outline (rims, footprints, skirts, pools) is distorted by angular and 2D noise, so nothing is a circle.
* Walls are cut by `erodedTop`: horizontal bands make ledges and notches, and since nothing overhangs, there are no floating fragments.
* Volcanic flanks use a noisy mix of loose material instead of horizontal strata, which would draw concentric rings on a smooth cone.
* Basins seal their walls automatically. Cascades and breached calderas opt out of sealing exactly where water must flow.

## Verify

Run `./gradlew :26.2:runSelfCheck -Pshowcase=landform -Prender=true [-Ponly=cone_sea,cascade]`.
