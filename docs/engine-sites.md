# Site engine: large real-place biomes at their real elevation

Every real-place biome is **site-placed**. A site is one large, roughly round area of a single biome, 1.2 to 1.7 times a normal biome (vast places up to about 1,600 blocks; twice that with the Large Biomes preset). The terrain inside it is moved to the place's real elevation, and the edge fades smoothly into the surrounding land. The three generic biomes (archipelago, polder, sunken pines) still take vanilla climate slots through TerraBlender.

The engine works with every world preset (default, Large Biomes, Amplified) and with terrain mods that replace the vanilla density functions (Terralith, Tectonic, JJThunder To The Max, Montane, Mountain Peaks, Cascades). It does not override any vanilla data file; it hooks the noise router at runtime.

## How it works

1. **Layout** (`site/SiteField`). The overworld is cut into square cells (`specs/regions.json` → `sites.cell`, 2048 blocks; ×2 on Large Biomes). Each cell may hold one site at a jittered centre. The climate at that centre (temperature, humidity, continentalness, erosion, read from the world's own noise router, so a terrain mod's climate counts) decides which biomes may go there, and one of them is picked by weight. When two sites would overlap, the one with the higher priority hash wins. A site can only be removed by a site that itself survives, so the result never depends on chunk order. Outlines are lobed circles, domain-warped by the seeded `biomesmod:site` noise. Everything is a pure function of the world seed.
2. **Router hook** (`mixin/RandomStateMixin`, `site/SiteRouter`). When a dimension's random state is created, the router of every overworld-like dimension (world floor below 0, a real sea) gets three wrappers before its noises are wired: around the final density, around the climate depth, and around the preliminary surface (`initial_density_without_jaggedness` in 1.21.1, `preliminary_surface_level` in 26.2). Whatever built the router (a vanilla preset or a terrain mod), its own terrain is what gets moved.
3. **Terrain** (`site/SiteDensity`, type `biomesmod:site`). For each column the engine finds the original smooth surface S0 (where the router's depth crosses zero) and the site's new surface `S = target + relief × (S0 − 72)` (mountain sites only lift: `max(S0, …)`). The shift `delta = w × (S − S0)` fades in with the site weight w across the ramp. Below the new surface, block height y shows the original terrain at `y − delta`, so caves, ores and overhangs move with it. Above it, the original terrain is compressed vertically by the site's detail factor (plateau and basin 0.06, lift `max(0.15, relief)`, mountain 1), so peaks shrink and plateaus come out flat; plateaus and basins also get a solid, flat profile in the top few blocks. The final density is sampled on its own 4×8×4 corner grid and interpolated, like vanilla does, so the cost stays close to vanilla inside sites and is zero outside them.
4. **Biome** (`mixin/MultiNoiseBiomeSourceMixin`, `site/SiteBiomes`). Inside the inner part of a site (weight ≥ `biome_threshold`, default 0.5), the biome source returns the site biome instead of the climate lookup. This applies from the sky down to about 64 blocks under the moved surface (the depth wrapper keeps the climate depth relative to it); deeper, vanilla cave biomes stay. The layout is read from the same field as the terrain, so terrain and biome always agree.
5. **Possible biomes.** Site biomes are added to a TerraBlender region at an unreachable climate point. This makes them part of the biome source's possible biomes, which feature decoration, `/locate` and structure checks rely on, while the climate lookup never returns them.

Elevation: `y = 63 + metres × 0.039` (1 block = 25.6 m), capped at 250. Salar de Uyuni (3,656 m) sits at y=205, the Puna at y=236 and the Lake Vostok ice plateau at y=200.

## Spec: `placement.site`

```jsonc
"placement": {"site": {
  "zone": ["mid_inland", "far_inland"],          // continentalness band(s) of the centre, or "continentalness": [min, max]
  "temperature": ["temperate", "warm", "hot"],   // frozen, cold, temperate, warm, hot (adjacent bands)
  "humidity": ["arid", "dry", "neutral"],        // arid, dry, neutral, wet, humid
  "erosion": [-1.0, 1.0],                        // optional
  "size": [620, 700],                            // core radius range in blocks (drawn per site)
  "ramp": 320,                                   // fade width in blocks
  "terrain": "plateau",                          // keep | plateau | lift | mountain | basin
  "elevation_m": 3656,                           // real elevation (mapped by config/biomesmod.json), or "target_y": 231
  "ridges": 90,                                  // optional: ridged mountain ranges up to this many blocks over the target
  "zones": [{"biome": "biomesmod:socotra_highlands", "from": 0.62, "to": 1.0}],  // optional concentric sub-biomes
  "channels": {"count": 1, "width": 70, "depth": 28},                            // optional fjord channels to the sea
  "relief": 0.02,                                // share of the original hills kept (0 = flat)
  "weight": 1.3,                                 // share among the biomes that accept a centre
  "biome_threshold": 0.5
}}
```

| Terrain | New surface | Above the surface | For |
|---|---|---|---|
| `keep` | unchanged | unchanged | oceans, coasts (landforms and shapers build the place) |
| `plateau` | target + relief × hills | flattened (0.06), flat top profile | salt flats, ice plateaus, mesas, canyon rims |
| `lift` | target + relief × hills | compressed by relief (≥ 0.15) | high steppe, uplands, volcanic highlands |
| `mountain` | max(original, target + relief × hills) | kept | massifs, alpine valleys, fjords |
| `basin` | target + relief × hills | flattened (0.06), flat top profile | wetlands, floodplains, depressions |

### Elevation

`elevation_m` is turned into a target height by `config/biomesmod.json` (`elevation`): below `highland_metres` (2,000 m)
one block is `metres_per_block` (25.6 m) above `sea_level_y` (63); from 2,000 m up the ground starts at
`highland_min_y` (190) and climbs `highland_metres_per_block` (40 m) per block, capped at `max_target_y` (300). Final
heights approach `max_target_y` + 12 smoothly (a soft ceiling), so high massifs never flatten against the build limit.

### Ridges, zones, channels

* **Ridges:** a domain-warped ridged multifractal (sharp crests, arêtes, broad lows, in the manner of Tectonic and
  Mountain Peaks) adds up to `ridges` blocks (times `ridge_scale` in the config). It rises only inside the site (a
  smoothstep of the site weight), so the ranges fade out before the edge.
* **Zones:** concentric sub-biomes by distance from the centre (1 at the centre, 0 at the outer edge, borders jittered).
  A zone biome's spec has `"placement": {"zone_of": "<site biome>"}`. A zone from 0 or to 1 is open on that side.
* **Channels:** fjords cut from the centre towards the lowest original ground (the sea), meandering, widening and
  deepening seawards, carved below sea level so the sea floods them.

## In game

`/biomesmod sites` lists the nearest sites (centre, size, terrain, target height). `/biomesmod sites <biome>` finds the nearest site of one biome within 40,000 blocks. Sites are large, so they are rarer than vanilla biome patches; with the default grid a given biome appears roughly every 15 to 25 km.

## Verify

`runSelfCheck` locates each site biome from the layout (nearest site within 60,000 blocks). It then checks the biome at the centre and logs the noise terrain over the core: median, min and max height against the target, flat area, and share under the sea. A plateau with a target of y ≥ 200 fails unless it holds at least 600,000 m² of flat land at y ≥ 200. `-Ppreset=minecraft:large_biomes` (or `minecraft:amplified`) runs it on another preset, `-PextraMods=<dir>` adds every mod jar in a folder and `-Pdatapacks=<dir>` every data pack zip, for compatibility tests. `-Poverview=true` writes a 2.2 km noise-terrain overview of every site (`<biome>_overview.png`) without generating chunks. `-Prender=true` writes the full top-down and section renders (`-PrenderSize=192` for a smaller, faster area).
