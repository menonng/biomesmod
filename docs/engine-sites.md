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
  "temperature": ["temperate", "warm", "hot"],   // polar, frozen, cold, temperate, warm, hot (adjacent bands)
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

A biome may have a second site spec, `placement.site_also`, with the same keys: Krenitsyn's caldera stands on land and, by the second spec, on a volcanic island of its own out in the deep ocean. Use `erosion` to keep high places among mountains and uplands (the high plateaus take `[-1, -0.05]`) and `continentalness` below about -0.45 for islands far from any coast (Socotra).

### Elevation

`elevation_m` is turned into a target height by `config/biomesmod.json` (`elevation`): below `highland_metres` (2,000 m)
one block is `metres_per_block` (25.6 m) above `sea_level_y` (63); from 2,000 m up the ground starts at
`highland_min_y` (190) and climbs `highland_metres_per_block` (40 m) per block, capped at `max_target_y` (300). Final
heights approach `max_target_y` + 12 smoothly (a soft ceiling), so high massifs never flatten against the build limit.

### Ridges, zones, channels

* **Ridges:** mountain ranges of up to `ridges` blocks (times `ridge_scale` in the config). A domain-warped ridged
  base gives the ranges (sharp crests, broad lows). The detail on top follows the method of Tectonic's mountain ridges
  (Tectonic is MIT-licensed; the method was reimplemented, no data copied): the detail noise is read at a point pushed
  away by the steepness of the base (`shift = |base slope| x 110 blocks`, per axis), which drags the small ridges down
  the flanks into spurs and gullies; a weathering noise thins it out in places. The ranges rise only inside the site
  (a smoothstep of the site weight), so they fade out before the edge.
* **Zones:** concentric sub-biomes by distance from the centre (1 at the centre, 0 at the outer edge, borders jittered).
  A zone biome's spec has `"placement": {"zone_of": "<site biome>"}`. A zone from 0 or to 1 is open on that side.
* **Channels:** fjords. For the main fjord, rays are cast from the site in 48 directions and it takes the one that
  reaches open water soonest: the original surface below sea level, which is the sea or a river valley (whose river
  carries it on to the sea). It is cut all the way to that water, up to 1.6 site radii out, past the edge of the site
  and through the neighbouring biome if it has to, so it joins the water around it instead of ending in a wall of
  land. Its meanders die out towards the mouth. If no water is in reach it heads for the lowest ground on the rim.
  Open water means 40 blocks of it (the sea or a bay, not a river). Arms come down from inland and join the main
  fjord at separate points on alternating sides; every channel swells and narrows along its length and widens
  towards the sea. All are carved below sea level, so the sea floods them. The
  self-check logs, for up to 40 sites of each fjord biome, how many reach open water.

## In game

`/biomesmod sites` lists the nearest sites (centre, size, terrain, target height). `/biomesmod sites <biome>` finds the nearest site of one biome within 40,000 blocks. Sites are large, so they are rarer than vanilla biome patches; with the default grid a given biome appears roughly every 15 to 25 km.

## Verify

`runSelfCheck` locates each site biome from the layout (nearest site within 60,000 blocks). It then checks the biome at the centre and logs the noise terrain over the core: median, min and max height against the target, flat area, and share under the sea. A plateau with a target of y ≥ 200 fails unless it holds at least 600,000 m² of flat land at y ≥ 200. `-Ppreset=minecraft:large_biomes` (or `minecraft:amplified`) runs it on another preset, `-PextraMods=<dir>` adds every mod jar in a folder and `-Pdatapacks=<dir>` every data pack zip, for compatibility tests. `-Poverview=true` writes a 2.2 km noise-terrain overview of every site (`<biome>_overview.png`) without generating chunks. `-Prender=true` writes the full top-down and section renders (`-PrenderSize=192` for a smaller, faster area).

### The polar band

`polar` is the coldest slice of the vanilla temperature range (below -0.65); `frozen` now starts above it. Only the
Antarctic biomes (Ross Ice Shelf, Antarctic Peninsula, McMurdo Dry Valleys, Lake Vostok) accept it, so they only ever
border each other and vanilla terrain, never another site biome.

### Island sites

`"island": true` in a site spec makes the site an island of its own. It is only placed out at sea (continentalness
below the coast at half or more of 32 points on two rings round its core), its land takes the inner part of the site (weight
above 0.5), and round that the ground is lowered to 12 blocks below sea level wherever it is higher: a ring of water
always parts the island from other land. Socotra, Alaid, Ushishir, the Exuma bank and Krenitsyn's sea variant use it.

## Villages in sites

Vanilla lays a village out from one height and relies on its village biomes being flat. Two rules keep villages of this mod's biomes on the ground:

* A village or outpost (any jigsaw structure with thin terrain adaptation) only starts in a mod biome where the ground within 40 blocks of its centre differs by at most 6 blocks (`LevelGround`, applied by `JigsawStructureMixin`). Vanilla biomes are not affected.
* Terrain shapers and landforms are built by features, after structures were planned. While a village is planned, the generator's height queries therefore include the ground they will build (`LandformHeights`, applied by `ChunkGeneratorHeightMixin`): each shaper of the biome is asked for its column (`ChunkFrame.plan`, without a generated chunk) and each landform for its top, in feature order. The level-ground check sees a canyon or a volcano, and houses are set on the shaped ground.
* Only villages and pillager outposts are planned this way. In a biome with a terrain shaper every other surface structure stays off (see "Biome tags and vanilla structures" in `biome-specs.md`), and uninhabited places carry `"structures": "none"`.
* A village does not start on water (a fjord, a lagoon, a salt lake).

The self-check logs how the ground meets a village (`village at ...: ground minus floor ...`) and how far the planned height is from the generated ground (`planned heights round the ...`); with `-Pbiomes=` it also measures the nearest village when none stands in the biome.

## Where a site meets the sea

A `lift` or `mountain` site without fjord channels whose `target_y` stands well above the sea (fully from 30 blocks up) eases its ground round sea level: within 10 blocks above and 8 below, the height is bent towards the waterline (power 1.7), which leaves a low strand and a shallow shelf instead of a slope running straight into the water. A biome can name the material of that strand with `surface.shore` (a block for the ground between y 57 and 64).

## What a site stands next to

A site is picked by the climate at its centre, so on its own an ice sheet could end up beside a forest and a salt
desert beside snow. Two rules soften the join (`SiteField.fitsAround`, `Site.shape`):

* **Surroundings.** A biome whose whole temperature range is frozen (`temperature[1] <= -0.45`) is only placed where
  the land round it is cold too: at 13 or more of 16 points on the outer edge of the site the temperature must be
  `<= -0.3`. A biome of the hot band (`temperature[0] >= 0.55`) needs `>= 0.2` at 13 of 16. This costs sites: the
  largest ones (Lake Vostok) keep about half of their places.
* **Outline.** The outline has five lobes of falling size instead of three, and the domain warp of the site grid is
  `sites.warp` = 130 blocks, so an edge reads less as a circle. The lobes must sum to 0.3 or less (the reach of a site
  is `core * 1.3 + ramp`).

Other biomes are not checked: a temperate site may still stand next to anything the vanilla climate puts there.
