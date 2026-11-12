# Terrain engine: `biomesmod:terrain_shaper`

A column-based feature that reshapes the ground of a biome. It runs once per chunk (`"placement": []`, step `raw_generation`) and writes only inside that chunk. Every cross-chunk decision is a pure function of the world seed and coordinates, so chunk borders are seamless.

## Pipeline per chunk

1. **Edge field.** Biome distance is sampled on a coarse grid (`biomes`). The change fades in over `edge_blend` blocks, and that fade band wanders with `edge_noise`.
2. **Read columns.** The frame reads the current ground, water and surface blocks.
3. **Mode.** The mode fills a `Column`: target ground (fractional), water level, surface override, `minGround` (dikes and rims), and flags such as `FALL` (waterfall column) and `NO_WALL`.
4. **Resolve.** Ground blends with the original by the edge factor. Water is placed only where the edge factor is at least 0.5. Neighbouring water is held back by automatic walls.
5. **Write.** Columns are raised, carved and dressed with the palette. Strata appear on cliff faces, caves within `seal_depth` are sealed, and falling blocks are never left over voids.

## Settings shared by every mode

```jsonc
{
  "type": "biomesmod:terrain_shaper",
  "config": {
    "mode": "dunes",
    "biomes": ["biomesmod:my_biome"],  // empty = everywhere (showcases only)
    "edge_blend": 32,
    "edge_noise": 0.35,
    "seed": 0,
    "seal_depth": 6,
    "palette": {
      "top": [{"block": {"Name": "minecraft:sand"}, "depth": 3}],
      "underwater": [...],
      "strata": {"strata": [{"block": {...}, "thickness": 4}]},
      "patches": [{"block": {...}, "coverage": 0.2, "scale": 24}],
      "cliff_slope": 3,
      "fluid": {"Name": "minecraft:water"}
    }
  }
}
```

## Modes

| Mode | For | Key parameters (defaults) |
|---|---|---|
| `flatten` | salt pans: Uyuni, Gairdner, Dallol, Tara | `level` or `offset` (2 above sea), `relief` 0, `water_coverage` 0 (1-block mirror film), `water_scale` 160, `crust` 0 (polygon ridges), `crust_cell` 7, `crust_block`, `film_block` (pave the film patches with a block instead of water: Uyuni's `salt_mirror`) |
| `depression` | dry land below sea level (polders) | `depth` 6, `relief` 1.2, `dike_height` 2, `dike_width` 4, `ditch_spacing` 0, `ditch_depth` 2. The dike crest sits where the edge factor is ≥ 0.5, so nothing inside can keep sea water. Use a small `edge_blend` (~10). |
| `dunes` | Lençóis (lagoons), Namib (star) | `style` `transverse`/`linear`/`star`, `height` 10, `wavelength` 40, `direction` 20, `level`/`base_offset`, `lagoon_depth` 0, `pan_coverage` 0, `pan_block`. No barchan crescents, by design. |
| `terraces` | Pamukkale, Plitvice | `step` 3, `pool_depth` 1, `rim_width` 1.3, `tilt` 30, `tilt_scale` 260, `lobe` 1, `lobe_scale` 40, `falls` 0, `pool_coverage` 1 (below 1 = lake chain inside a noise mask), `pool_scale` 180, `lift` 0 |
| `canyon` | Grand Canyon | `plateau` 18, `depth` 70, `width` 140, `floor_width` 14, `benches` 5, `direction` 35, `spacing` 520, `side_spacing` 150, `river_width` 4 |
| `u_valley` | Lauterbrunnen, Sarek, Dry Valleys; fjords with `flood_to_sea` | `shoulder` 50, `floor_depth` 10, `width` 110, `floor_width` 40, `steepness` 5, `direction` 0, `spacing` 600, `falls` 1 per 100 blocks, `flood_to_sea` false |
| `ice_sheet` | Vostok, Franz Josef Land, Svalbard; Ross shelf with `shelf` | `thickness` 14, `dome` 20, `dome_scale` 300, `shelf` false, `freeboard` 12, `roughness` 1.2, `crevasses` {spacing, width, depth, coverage, scale, length} |
| `glacier` | Aletsch, Denali, Patagonia, Darwin | `drop` 6 (ice below the smoothed terrain), `blur` 6, `moraine_spacing` 48, `snowline` 150, `crevasses` |
| `wetland` | Everglades, Amazon várzea | `water_offset` 0, `dry_coverage` 0.45, `hummock` 2, `shallow` 1, `channels` 0.25, `scale` 70 |
| `yardangs` | Dasht-e Lut kaluts | `height` 22, `spacing` 34, `length` 160, `width` 0.42, `direction` -20, `base_offset` 0 |
| `rolling` | steppe, mesetas, plateau smoothing | `smooth` 0.8, `hills` 5, `hill_scale` 180, `lift` 0, `mesa_step` 0, `lift_ramp` 0 (0: the lift rises straight from the shore as a cliff; N: it grows over the first N blocks above the sea). Never fills the sea or large lakes; narrow rivers and ponds inside land are lifted with it. |
| `islands` | archipelagos, sandbars, cays, reefs | `coverage` 0.22, `scale` 70, `height` 9, `beach` 2, `depth` 6, `rocky` 0.15 |

## Naturalness built in

* Outlines are domain-warped multi-octave noise.
* Canyon and valley centre lines come from `Meander`, whose cross-slope is limited so they can never loop into rings or crescents.
* Terrace rims meander along warped contours.
* Water levels are pure functions, so neighbouring chunks agree, and walls keep every basin closed.
* Waterfall tops are source blocks, so falls keep running.

## Verify

Run `./gradlew :26.2:runSelfCheck -Pshowcase=field -Prender=true [-Ponly=dunes_star,canyon]`. Renders are written to `versions/26.2/run/selfcheck/renders/field_*.png`.
