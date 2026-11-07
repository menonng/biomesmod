# Site engine: large real-place biomes at their real elevation

Every real-place biome is **site-placed**. A site is one large, roughly round area of a single biome, typically 800 to 1,600 blocks across. The terrain inside it is moved to the place's real elevation, and the edge fades smoothly into the surrounding land. The five generic coast biomes (eroded shore, eroded cliffs, archipelago, polder, sunken pines) still take vanilla climate slots through TerraBlender.

## How it works

1. **Layout** (`site/SiteField`). The overworld is cut into square cells (`specs/regions.json` → `sites.cell`, 2048 blocks). Each cell may hold one site at a jittered centre. The vanilla climate at that centre (temperature, humidity, continentalness, erosion) decides which biomes may go there, and one of them is picked by weight. When two sites would overlap, the one with the higher priority hash wins. A site can only be removed by a site that itself survives, so the result never depends on chunk order. Outlines are lobed circles, domain-warped by the seeded `biomesmod:site` noise. Everything is a pure function of the world seed.
2. **Terrain** (`site/SiteDensity`, type `biomesmod:site`). The vanilla overworld `offset`, `factor` and `jaggedness` density functions are wrapped (data overrides generated from vanilla's own JSON into `data/minecraft/worldgen/density_function/overworld/`). Vanilla terrain, caves, aquifers and surface rules are all built from these three functions. So a site that asks for flat land at y=205 gets a real plateau, with natural slopes up to it across the ramp. No chunk-local post-processing is involved.
3. **Biome** (`mixin/MultiNoiseBiomeSourceMixin`, `site/SiteBiomes`). Inside the inner part of a site (weight ≥ `biome_threshold`, default 0.5), the biome source returns the site biome instead of the climate lookup. This applies from the sky down to about 64 blocks underground; deeper, vanilla cave biomes stay. The layout is read from the same field as the terrain, so terrain and biome always agree.
4. **Possible biomes.** Site biomes are added to a TerraBlender region at an unreachable climate point. This makes them part of the biome source's possible biomes, which feature decoration, `/locate` and structure checks rely on, while the climate lookup never returns them.

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
  "elevation_m": 3656,                           // or "target_y": 205
  "relief": 0.02,                                // share of the vanilla hills kept (0 = flat)
  "weight": 1.3,                                 // share among the biomes that accept a centre
  "biome_threshold": 0.5
}}
```

| Terrain | Offset (height) | Factor | Jaggedness | For |
|---|---|---|---|---|
| `keep` | vanilla | vanilla | vanilla | oceans, coasts (landforms and shapers build the place) |
| `plateau` | target + relief × hills | ≥ 12 (flat) | 0 | salt flats, ice plateaus, mesas, canyon rims |
| `lift` | target + relief × hills | vanilla | × relief | high steppe, uplands, volcanic highlands |
| `mountain` | max(vanilla, target + relief × hills) | vanilla | vanilla | massifs, alpine valleys, fjords |
| `basin` | target + relief × hills | ≥ 9 | 0 | wetlands, floodplains, depressions |

## In game

`/biomesmod sites` lists the nearest sites (centre, size, terrain, target height). `/biomesmod sites <biome>` finds the nearest site of one biome within 40,000 blocks. Sites are large, so they are rarer than vanilla biome patches; with the default grid a given biome appears roughly every 15 to 25 km.

## Verify

`runSelfCheck` locates each site biome from the layout (nearest site within 60,000 blocks). It then checks the biome at the centre and logs the noise terrain over the core: median, min and max height against the target, flat area, and share under the sea. A plateau with a target of y ≥ 200 fails unless it holds at least 600,000 m² of flat land at y ≥ 200. `-Poverview=true` writes a 2.2 km noise-terrain overview of every site (`<biome>_overview.png`) without generating chunks. `-Prender=true` writes the full top-down and section renders (`-PrenderSize=192` for a smaller, faster area).

Other world presets (Large Biomes, Amplified) use their own terrain functions, which are not wrapped, so they get no sites.
