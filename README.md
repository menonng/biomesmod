# Biomes Mod

A Fabric mod that adds Overworld, Nether and End biomes, each loosely inspired by a real place. It targets Minecraft **1.21.1** and **26.2** with Mojmap names and is written in **Kotlin**.

**Requires:** Fabric API, [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) and [TerraBlender](https://modrinth.com/mod/terrablender). TerraBlender places the biomes in every dimension. This is a Fabric mod only; there is no data-pack version.

**Gallery:** every real-place biome next to its real satellite image, a top-down map of the generated site and an in-game shader view: [docs/biomes/README.md](docs/biomes/README.md).

Global terrain changes (data overrides):

* **Vast coastlines (in the manner of Project Regon).** The continentalness noise is remapped so the coast band is stretched about 4 times with gentle slopes, and every beach gets wide sand flats, bars and shallow lagoons (`coast_flats`, added to all `#minecraft:is_beach` biomes).
* **No crescent water gorges.** Ravines are kept underground, so they no longer breach the surface near sea level and flood.

**Placement.** Every real-place biome is a *site*, 1.2 to 1.7 times the size of a normal biome depending on the place (naturally vast places such as salt flats, ice sheets, steppes, dune seas and reefs stay larger, up to about 1,600 blocks across), placed by the site engine ([docs/engine-sites.md](docs/engine-sites.md)). The terrain inside a site is moved to the place's real elevation, and a ramp joins it smoothly to the land around it. Below 2,000 m one block is 25.6 m above sea level (y=63); real high ground from 2,000 m up starts at **y=190** and climbs 40 m per block, so Salar de Uyuni (3,656 m) is a flat salt plateau at y=231 and Grand Prismatic (2,240 m) sits at y=196. Mountain sites add ridged ranges on top (sharp crests and arêtes in the manner of Tectonic), fading out towards the edge. A site can hold several zone biomes (Socotra: highlands, dragon's blood plateau, coast), and fjord sites cut channels down to the sea. Sites are chosen from the world's climate at their centre, so deserts stay among dry land and ice plateaus among frozen land. About a quarter of the world is in sites. The three generic biomes (archipelago, polder tulip fields, sunken pines) still take vanilla climate slots through TerraBlender. In game, `/biomesmod sites [biome]` lists the nearest sites.

**Works with other worldgen.** The site engine hooks the world's noise router at runtime instead of overriding vanilla files, so sites appear on every world preset (Default, Large Biomes with sites twice as large, Amplified) and on top of terrain mods. Tested combinations are listed under [Compatibility](#compatibility).

**Config:** `config/biomesmod.json` (written on first start) sets the elevation mapping (sea level, metres per block, the 2,000 m / y=190 highland rule, the 40 m per block above it, the height cap), site cell size, site chance, size scale, Large Biomes scale, per-biome site weights (0 turns a biome off), the height of the ridged ranges and what the Uyuni mirror is made of (`uyuni.mirror`: `block` or `water`).

**New blocks** (84; the natural ones are in the Natural Blocks creative tab, the worked wood in Building Blocks; textures carry LabPBR normal and specular maps for shader packs, so the salt flats, ice and wet sinter shine and the star blocks glow under Bliss and other PBR shaders):

* Socotra: dragon's blood log, wood, leaves and sapling, desert rose.
* Geothermal: geyserite, geyser vent (erupts every 1-3 minutes and throws you up; in 26.2 geyser cones use vanilla's potent sulfur geysers instead), steam vent, orange / yellow / green thermal mats, sulfur crust, travertine.
* Salar de Uyuni: salt mirror, the flooded salt flat as a solid, dead-calm block whose LabPBR material (smoothness 0.98) mirrors the sky under shader packs with specular reflections switched on (in Bliss: LabPBR → Specular Reflections, Screen Space Reflections, Sky Reflection; they are off by default). Without a shader pack it is a plain pale-blue block. See [docs/uyuni-mirror.md](docs/uyuni-mirror.md).
* Dry highlands and steppe: tussock grass (paja brava of the Puna, coirón of Patagonia, feather grass of the Mongolian steppe; also roots in sand, gravel and terracotta) and yareta, the green cushion plant of the high Andes.
* Rock and sand: salt crust, white sand (Lençóis, replaces concrete powder), volcanic ash, scoria, dolerite, glacier ice.
* White Old Growth: ghostwood log, wood, leaves, hollow (breathes wisps) and sapling (nine in a 3x3 square grow into an ancient ghostwood, the giant of the biome's heart), pale moss and moss carpet, pale fern, silver beard lichen, moon bracket fungus, moonpetal.
* Giant Redwood Forest: redwood log, wood, leaves and sapling (one grows into a young redwood, four in a 2x2 square into a giant).
* Worked wood: dragon's blood, ghostwood and redwood each come as stripped log and wood (use an axe), planks, stairs, slab, fence, fence gate, door, trapdoor, pressure plate and button, with the vanilla recipes. There are no signs or boats.
* Potted plants: the three saplings, desert rose, moonpetal and pale fern go into a flower pot.
* Star Abyss: abyss stone, starlit abyss stone, stardust, star crystal and crystal cluster, star bloom (grows on any face), astral roots, star spring (a fountain of starlight that lifts you up to the floating isles).

The blocks behave like their vanilla counterparts: wood, leaves and plants burn, logs, planks and saplings are furnace fuel, leaves drop saplings and sticks, plants and leaves go into the composter, stone needs a pickaxe, and each block has its note block sound. Other recipes: pale moss carpet from pale moss, pink / white / light blue dye from desert rose / moonpetal / star bloom, glass from white sand. Every recipe is unlocked in the recipe book by picking up its ingredient.

All block textures are 16x16 pixel art drawn by `tools/blocks/art.py` (a small hue-shifted colour ramp per material, a height field lit from the top left, hand-drawn plant sprites); the whole set, each solid block tiled 2x2: [docs/block-textures.png](docs/block-textures.png).

Because of them, clients need the mod installed to join a server that runs it.

| Biome | Name | Dimension | Placement | Place n (docs/places.md) |
|---|---|---|---|---|
| `alaid_volcano` | Alaid Volcano / 알라이드 화산 | overworld | site, lift y=67 | 3 |
| `aletsch_glacier` | Aletsch Glacier / 알레치 빙하 | overworld | site, mountain y=208 (2700 m), ridges | 25 |
| `alkefjellet` | Alkefjellet / 알케피엘레 | overworld | site, keep | 43 |
| `amazon_varzea` | Amazon Várzea / 아마존 바르제아 | overworld | site, basin y=64 | 37 |
| `antarctic_peninsula` | Antarctic Peninsula / 남극 반도 | overworld | site, mountain y=100, ridges | 47 |
| `bromo_tengger` | Bromo Tengger / 브로모 텡게르 | overworld | site, lift y=192 (2100 m) | 17 |
| `chorus_thicket` | Chorus Thicket / 코러스 덤불 | end | end highlands | — |
| `cliffs_of_moher` | Cliffs of Moher / 모허 절벽 | overworld | site, keep | 40 |
| `coast_archipelago` | Archipelago / 열도 | overworld | warm_ocean (+2) | — |
| `coast_polder` | Polder / 간척 저지대 | overworld | site, basin y=64, 1 zones | — |
| `coast_polder_tulips` | Polder Tulip Fields / 간척지 튤립 평원 | overworld | zone of `coast_polder` | — |
| `coast_sunken_pines` | Sunken Pine Barrens / 저지대 성긴 침엽수림 | overworld | taiga (+2) | — |
| `cordillera_darwin` | Cordillera Darwin / 다윈 산맥 | overworld | site, mountain y=126 (1600 m), ridges | 49 |
| `daintree` | Daintree Rainforest / 데인트리 열대우림 | overworld | site, keep | 16 |
| `dallol` | Dallol / 달롤 | overworld | site, basin y=60 | 32 |
| `danakil_flats` | Danakil Flats / 다나킬 평원 | nether | nether | — |
| `dasht_e_lut` | Dasht-e Lut / 다쉬테 루트 | overworld | site, plateau y=79 | 15 |
| `denali_massif` | Denali Massif / 디날리 산괴 | overworld | site, mountain y=200 (2400 m), ridges | 21 |
| `devils_tower` | Devils Tower / 데빌스 타워 | overworld | site, lift y=110 (1200 m) | 11 |
| `everglades` | Everglades / 에버글레이즈 | overworld | site, basin y=63 | 36 |
| `exuma_cays` | Exuma Cays / 엑수마 케이스 | overworld | site, basin y=59 | 52 |
| `franz_josef_land` | Franz Josef Land / 프란츠요제프 제도 | overworld | site, keep | 44 |
| `geirangerfjord` | Geirangerfjord / 예이랑에르피오르 | overworld | site, mountain y=122 (1500 m), ridges | 27 |
| `giant_redwood_forest` | Giant Redwood Forest / 거대나무숲 | overworld | site, lift y=84 | — |
| `giants_causeway` | Giant's Causeway / 자이언츠 코즈웨이 | overworld | site, keep | 39 |
| `glacier` | Glacier / 빙하 | overworld | site, mountain y=212 (2900 m), ridges | — |
| `gotjawal` | Gotjawal / 곶자왈 | overworld | site, lift y=75 | — |
| `grand_canyon` | Grand Canyon / 그랜드 캐니언 | overworld | site, plateau y=192 (2100 m) | 34 |
| `grand_prismatic` | Grand Prismatic / 그랜드 프리즈매틱 | overworld | site, plateau y=196 (2240 m) | 8 |
| `great_barrier_reef` | Great Barrier Reef / 그레이트 배리어 리프 | overworld | site, basin y=55, island | 41 |
| `ha_long_bay` | Ha Long Bay / 하롱베이 | overworld | site, basin y=55, island | 22 |
| `iguazu_falls` | Iguazu Falls / 이구아수 폭포 | overworld | site, lift y=72 | 9 |
| `krenitsyn_caldera` | Krenitsyn Caldera / 크레니친 칼데라 | overworld | site, lift y=81 (450 m) | 2 |
| `lake_gairdner` | Lake Gairdner / 게어드너 호 | overworld | site, plateau y=68 | 42 |
| `lake_vostok_plateau` | Vostok Plateau / 보스토크 고원 | overworld | site, plateau y=227 (3488 m) | 50 |
| `lauterbrunnen` | Lauterbrunnen Valley / 라우터브루넨 계곡 | overworld | site, mountain y=135 (1850 m), ridges | 23 |
| `lencois_maranhenses` | Lençóis Maranhenses / 렌소이스 마라냥세스 | overworld | site, keep | 7, 20 |
| `lofoten_reine` | Lofoten Peaks / 로포텐 봉우리 | overworld | site, keep | 26, 28 |
| `magellanic_fjords` | Magellanic Forest / 마젤란 숲 | overworld | site, mountain y=87 (620 m), ridges | 48 |
| `mcmurdo_dry_valleys` | McMurdo Dry Valleys / 맥머도 드라이 밸리 | overworld | site, mountain y=98, ridges | 46 |
| `monument_valley` | Monument Valley / 모뉴먼트 밸리 | overworld | site, plateau y=129 (1700 m) | 14 |
| `moraine_lake` | Moraine Lake / 모레인 호수 | overworld | site, mountain y=137 (1885 m), ridges | 35 |
| `orkhon_steppe` | Orkhon Steppe / 오르혼 초원 | overworld | site, lift y=124 (1550 m) | 51 |
| `pamukkale` | Pamukkale / 파묵칼레 | overworld | site, lift y=80 | 12 |
| `patagonian_icefield` | Patagonian Ice Field / 파타고니아 빙원 | overworld | site, lift y=122 (1500 m), ridges | 10 |
| `patagonian_steppe` | Patagonian Steppe / 파타고니아 스텝 | overworld | site, lift y=72 | 19 |
| `plitvice_lakes` | Plitvice Lakes / 플리트비체 호수 | overworld | site, lift y=95 | 6, 30 |
| `puna_de_atacama` | Puna de Atacama / 푸나 데 아타카마 | overworld | site, lift y=240 (4000 m) | 18 |
| `ross_ice_shelf` | Ross Ice Shelf / 로스 빙붕 | overworld | site, keep | 45 |
| `salar_de_tara` | Salar de Tara / 타라 염호 | overworld | site, plateau y=248 (4300 m) | 33 |
| `salar_de_uyuni` | Salar de Uyuni / 우유니 소금사막 | overworld | site, plateau y=231 (3656 m) | 4 |
| `sarek` | Sarek / 사렉 | overworld | site, mountain y=110 (1200 m), ridges | 29 |
| `socotra` | Socotra Dragon's Blood Plateau / 소코트라 용혈수 고원 | overworld | site, lift y=92 (750 m), ridges, 2 zones | 13 |
| `socotra_coast` | Socotra Coast / 소코트라 해안 | overworld | zone of `socotra` | 13 |
| `socotra_highlands` | Socotra Haggeher Highlands / 소코트라 하지르 고지대 | overworld | zone of `socotra` | 13 |
| `sossusvlei` | Sossusvlei / 소수스블레이 | overworld | site, lift y=85 | 38 |
| `star_abyss` | Star Abyss / 별의 심연 | end | end highlands | — |
| `tre_cime` | Tre Cime / 트레 치메 | overworld | site, lift y=198 (2300 m) | 24 |
| `ushishir_caldera` | Ushishir Caldera / 우시시르 칼데라 | overworld | site, keep | 1 |
| `wadi_rum` | Wadi Rum / 와디 럼 | overworld | site, plateau y=102 (1000 m) | 31 |
| `white_old_growth` | White Old Growth / 백색 고목림 | overworld | site, lift y=92, 1 zones | — |
| `white_plains` | White Plains / 백색 평원 | overworld | zone of `white_old_growth` | — |
| `zhangjiajie` | Zhangjiajie Pillars / 장자제 석주림 | overworld | site, lift y=90 | 5 |

## Compatibility

Last measured with 0.5.0-beta.1 on Minecraft 26.2 (self-check on a biome subset, seed 0); not yet re-run for 0.6.0:

| Configuration | Result |
|---|---|
| Default, Large Biomes, Amplified presets | passed, sites generate on every preset |
| Tectonic, Terralith (Lithostitched), Montane, Mountain Peaks, Hybrid Beta | passed |
| WWOO, OTBWG (1.21.1) | feature-order cycles, fixed with per-version order hints |
| JJThunder To The Max | its biome search hangs the locate step; not verified |

## Layout

```
common/                 shared Kotlin + resources (compiled into every version)
  src/gametest/         dev-only: engine showcases for the self-check server
versions/1.21.1/        Loom (remap) + Mojang mappings, Java 21, version-specific JSON + compat/ shims
versions/26.2/          Loom (unobfuscated), Java 25, version-specific JSON + compat/ shims
  src/shots/            dev-only: screenshot director for the gallery
docs/api-differences.md worldgen API differences between the two versions
docs/biomes/            the biome gallery
tools/biomegen/         spec generator (Python)
tools/gallery/          gallery page generator (Python)
```

To add a biome, follow `docs/biome-specs.md`:

1. Write `specs/biomes/<id>.json` (version-agnostic) and any `specs/features/<id>_*.json`.
2. Run `python -I tools/biomegen/generate.py`. This produces both versions' JSON, the TerraBlender placement and the lang files.
3. Run the self-check with `-Prender=true` and look at the renders.

The terrain engines are documented in `docs/engine-terrain.md` (`terrain_shaper`, 12 modes) and `docs/engine-landform.md` (`landform`, 11 shapes, plus the custom trees and decorations). Block textures and models come from `tools/blocks/make_blocks.py`. The real places behind the biomes are catalogued in `docs/places.md`.

To add a Minecraft version, create `versions/<mc>/` (gradle.properties, build.gradle, `compat/` shims, biome JSON) and add it to `settings.gradle`.

## Build and verify

```bash
./gradlew build                          # jars -> versions/*/build/libs
./gradlew :1.21.1:runSelfCheck :26.2:runSelfCheck
```

`runSelfCheck` boots a throwaway dedicated server on a fresh seed-0 world (`-Ppreset=minecraft:large_biomes` or `minecraft:amplified` for other presets, `-PextraMods=<dir>` / `-Pdatapacks=<dir>` to add other worldgen mods). For each biome it checks three things in that biome's dimension:

1. The biome is registered.
2. The dimension's biome source can produce it.
3. A locate search finds it in generated terrain (site biomes: the nearest site, its biome and its terrain against the target height).

It also logs the most common surface blocks found there. It exits non-zero on any failure.

For interactive testing, run `./gradlew :26.2:runClient` (or `:1.21.1:runClient`).

To test on a dedicated Fabric server, put these in its `mods/` folder: the mod jar, the Fabric API, Fabric Language Kotlin and TerraBlender versions from `versions/*/gradle.properties` and `gradle.properties`, and optionally Chunky for pre-generation. Set `level-seed=0` in `server.properties` to get the same world as `runSelfCheck`. The self-check log's coordinates then work directly with `/tp`, and the "nearest at" positions match `/execute positioned 0 64 0 run locate biome <id>`.
