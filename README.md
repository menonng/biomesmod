# Biomes Mod

A Fabric mod that adds Overworld, Nether and End biomes, each loosely inspired by a real place. It targets Minecraft **1.21.1** and **26.2** with Mojmap names.

**Requires:** Fabric API and [TerraBlender](https://modrinth.com/mod/terrablender). TerraBlender places the biomes in every dimension.

Global terrain changes (data overrides, also active on Paper via the data pack):

* **Wider coastlines.** The continentalness noise is remapped so the coast band is stretched about 3 times. Measured on the dev seed, beach plus shallow-shelf width went from 33 to 62 blocks.
* **No crescent water gorges.** Ravines are kept underground, so they no longer breach the surface near sea level and flood.

| Biome | Name | Dimension | Vanilla slot taken | Place n (docs/places.md) |
|---|---|---|---|---|
| `alaid_volcano` | Alaid Volcano / 알라이드 화산 | overworld | cold_ocean | 3 |
| `aletsch_glacier` | Aletsch Glacier / 알레치 빙하 | overworld | frozen_peaks | 25 |
| `amazon_varzea` | Amazon Várzea / 아마존 바르제아 | overworld | swamp | 37 |
| `antarctic_peninsula` | Antarctic Peninsula / 남극 반도 | overworld | snowy_slopes | 47 |
| `bromo_tengger` | Bromo Tengger / 브로모 텡게르 | overworld | windswept_savanna | 17 |
| `chorus_thicket` | Chorus Thicket / 코러스 덤불 | end | end | — |
| `cliffs_of_moher` | Cliffs of Moher / 모허 절벽 | overworld | stony_shore | 40 |
| `coast_archipelago` | Archipelago / 열도 | overworld | lukewarm_ocean | — |
| `coast_eroded_cliffs` | Eroded Stony Cliffs / 침식 암석 절벽 | overworld | stony_shore | — |
| `coast_eroded_shore` | Eroded Shore / 침식 해안 | overworld | beach | — |
| `coast_polder_tulips` | Polder Tulip Fields / 간척지 튤립 평원 | overworld | plains | — |
| `coast_sunken_pines` | Sunken Pine Barrens / 저지대 성긴 침엽수림 | overworld | taiga | — |
| `cordillera_darwin` | Cordillera Darwin / 다윈 산맥 | overworld | frozen_peaks | 49 |
| `daintree` | Daintree Rainforest / 데인트리 열대우림 | overworld | sparse_jungle | 16 |
| `dallol` | Dallol / 달롤 | overworld | eroded_badlands | 32 |
| `danakil_flats` | Danakil Flats / 다나킬 평원 | nether | nether | — |
| `dasht_e_lut` | Dasht-e Lut / 다쉬테 루트 | overworld | eroded_badlands | 15 |
| `denali_massif` | Denali Massif / 디날리 산괴 | overworld | jagged_peaks | 21 |
| `devils_tower` | Devils Tower / 데빌스 타워 | overworld | plains | 11 |
| `everglades` | Everglades / 에버글레이즈 | overworld | mangrove_swamp | 36 |
| `exuma_cays` | Exuma Cays / 엑수마 케이스 | overworld | lukewarm_ocean | 52 |
| `franz_josef_land` | Franz Josef Land / 프란츠요제프 제도 | overworld | snowy_plains | 44 |
| `geirangerfjord` | Geirangerfjord / 예이랑에르피오르 | overworld | windswept_forest | 27 |
| `giants_causeway` | Giant's Causeway / 자이언츠 코즈웨이 | overworld | beach | 39 |
| `gotjawal` | Gotjawal / 곶자왈 | overworld | forest | — |
| `grand_canyon` | Grand Canyon / 그랜드 캐니언 | overworld | badlands | 34 |
| `grand_prismatic` | Grand Prismatic / 그랜드 프리즈매틱 | overworld | taiga | 8 |
| `great_barrier_reef` | Great Barrier Reef / 그레이트 배리어 리프 | overworld | warm_ocean | 41 |
| `ha_long_bay` | Ha Long Bay / 하롱베이 | overworld | warm_ocean | 22 |
| `iguazu_falls` | Iguazu Falls / 이구아수 폭포 | overworld | jungle | 9 |
| `krenitsyn_caldera` | Krenitsyn Caldera / 크레니친 칼데라 | overworld | windswept_gravelly_hills | 2 |
| `lake_gairdner` | Lake Gairdner / 게어드너 호 | overworld | badlands | 42 |
| `lake_vostok_plateau` | Vostok Plateau / 보스토크 고원 | overworld | snowy_plains | 50 |
| `lauterbrunnen` | Lauterbrunnen Valley / 라우터브루넨 계곡 | overworld | meadow | 23 |
| `lencois_maranhenses` | Lençóis Maranhenses / 렌소이스 마라냥세스 | overworld | desert | 7, 20 |
| `lofoten_reine` | Lofoten Peaks / 로포텐 봉우리 | overworld | stony_shore | 26, 28 |
| `magellanic_fjords` | Magellanic Forest / 마젤란 숲 | overworld | old_growth_spruce_taiga | 48 |
| `mcmurdo_dry_valleys` | McMurdo Dry Valleys / 맥머도 드라이 밸리 | overworld | ice_spikes | 46 |
| `monument_valley` | Monument Valley / 모뉴먼트 밸리 | overworld | badlands | 14 |
| `moraine_lake` | Moraine Lake / 모레인 호수 | overworld | snowy_slopes | 35 |
| `orkhon_steppe` | Orkhon Steppe / 오르혼 초원 | overworld | sunflower_plains | 51 |
| `pamukkale` | Pamukkale / 파묵칼레 | overworld | savanna | 12 |
| `patagonian_icefield` | Patagonian Ice Field / 파타고니아 빙원 | overworld | frozen_peaks | 10 |
| `patagonian_steppe` | Patagonian Steppe / 파타고니아 스텝 | overworld | plains | 19 |
| `plitvice_lakes` | Plitvice Lakes / 플리트비체 호수 | overworld | birch_forest | 6, 30 |
| `puna_de_atacama` | Puna de Atacama / 푸나 데 아타카마 | overworld | stony_peaks | 18 |
| `ross_ice_shelf` | Ross Ice Shelf / 로스 빙붕 | overworld | frozen_ocean | 45 |
| `salar_de_tara` | Salar de Tara / 타라 염호 | overworld | badlands | 33 |
| `salar_de_uyuni` | Salar de Uyuni / 우유니 소금사막 | overworld | desert | 4 |
| `sarek` | Sarek / 사렉 | overworld | grove | 29 |
| `socotra` | Socotra Dragon's Blood / 소코트라 용혈수 | overworld | savanna_plateau | 13 |
| `sossusvlei` | Sossusvlei / 소수스블레이 | overworld | desert | 38 |
| `svalbard_coast` | Svalbard Coast / 스발바르 해안 | overworld | snowy_beach | 43 |
| `tre_cime` | Tre Cime / 트레 치메 | overworld | stony_peaks | 24 |
| `ushishir_caldera` | Ushishir Caldera / 우시시르 칼데라 | overworld | ocean | 1 |
| `wadi_rum` | Wadi Rum / 와디 럼 | overworld | desert | 31 |
| `zhangjiajie` | Zhangjiajie Pillars / 장자제 석주림 | overworld | jungle | 5 |

## Layout

```
common/                 shared Java + resources (compiled into every version)
  src/gametest/         dev-only: mixin that gives the self-check a normal (non-flat) world
  datapack/             pack.mcmeta per version for the standalone data pack
versions/1.21.1/        Loom (remap) + Mojang mappings, Java 21, version-specific JSON + compat/ shims
versions/26.2/          Loom (unobfuscated), Java 25, version-specific JSON + compat/ shims
docs/api-differences.md worldgen API differences between the two versions
```

To add a biome, follow `docs/biome-specs.md`:

1. Write `specs/biomes/<id>.json` (version-agnostic) and any `specs/features/<id>_*.json`.
2. Run `python -I tools/biomegen/generate.py`. This produces both versions' JSON, the TerraBlender placement and the lang files.
3. Run the self-check with `-Prender=true` and look at the renders.

The terrain engines are documented in `docs/engine-terrain.md` (`terrain_shaper`, 12 modes) and `docs/engine-landform.md` (`landform`, 9 shapes, plus `umbrella_tree`). The real places behind the biomes are catalogued in `docs/places.md`.

To add a Minecraft version, create `versions/<mc>/` (gradle.properties, build.gradle, `compat/` shims, biome JSON) and add it to `settings.gradle`.

## Build and verify

```bash
./gradlew build datapack                 # jars -> versions/*/build/libs, data packs -> versions/*/build/datapack
./gradlew :1.21.1:runSelfCheck :26.2:runSelfCheck
```

`runSelfCheck` boots Fabric's headless gametest server on a fresh normal world, so it needs no EULA and no client. For each biome it checks three things in that biome's dimension:

1. The biome is registered.
2. The dimension's biome source can produce it.
3. A locate search finds it in generated terrain.

It also logs the most common surface blocks found there. It exits non-zero on any failure.

For interactive testing, run `./gradlew :26.2:runClient` (or `:1.21.1:runClient`).

## Paper / vanilla servers

Paper cannot load Fabric mods or TerraBlender. The `datapack` task builds a data-only pack with the biome and feature definitions. On Paper the biomes are registered but do not generate naturally, and surface rules do not apply. To inspect one, force-load the chunk first, then fill the biome:

```
/forceload add 0 0
/fillbiome 0 60 0 15 90 15 biomesmod:gotjawal
```
