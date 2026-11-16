# Changelog

Versions are `major.minor.patch`, with `-beta.N` and `-rc.N` pre-releases. Each jar carries the Minecraft version after
a `+` (`biomesmod-0.6.0+26.2.jar`).

## 0.6.0

Everything since 0.5.0-beta.1, in short:

**Biomes and terrain**
* 64 biomes: 59 in the Overworld as sites or zones of a site, 2 generic coast biomes, 1 in the Nether, 2 in the End.
* Site engine: every real place is a large site at its real elevation, joined to the land round it by a ramp; mountain
  sites carry ridged ranges, fjord sites cut channels out to open water, island sites generate as islands.
* Terrain shapers (12 modes: salt flats, dunes, terraces, canyons, glacial valleys, ice sheets, shores and more) and
  landforms (cones, calderas, buttes, pillars, monoliths, karst islands, sea stacks, hot springs, geysers, cascades).
* Wide coasts everywhere, with the beach profile of the Regon data pack on every beach biome.
* Polar and frozen sites only stand where the land round them is cold, Dallol only where it is warm; site outlines are
  irregular.
* Villages in the mod's biomes are planned on the ground that the shapers and landforms will build, and only start on
  level, dry ground. Other surface structures are left out of biomes whose ground is rebuilt.
* New at the end of the betas: Takachiho Gorge (Japan), a narrow gorge with walls of columnar basalt in a forested plateau; Gotjawal
  rebuilt as the lava forest it is (clumps of thin crooked stems, many grown on mossy boulders that their roots grip and
  run down into the ground, ivy on the stems and hanging vines, collapse pits with
  fern and pools, lava rubble and domes, a floor of moss and stone).
* Waterfalls on steep faces, hamlets, wild trees and rocks, flowers and ground plants per biome.

**Blocks** (84)
* Natural blocks for salt flats, hot springs, volcanoes, glaciers, the white old growth and the star abyss, with LabPBR
  maps for shader packs.
* New in 0.6.0: stripped logs and woods, planks, stairs, slabs, fences, fence gates, doors, trapdoors, pressure plates and
  buttons for dragon's blood, ghostwood and redwood; dragon's blood and redwood saplings; six potted plants; 38 recipes
  with recipe book unlocks.
* New in 0.6.0: the blocks burn, compost, fuel furnaces, sound in note blocks and drop saplings and sticks as their
  vanilla counterparts do.

**Fixes since the last beta**
* The 1.21.1 jar declared `~1.21.1` and would have loaded (and crashed) on 1.21.2 to 1.21.11. It now requires 1.21.1.
* A wrong value in `config/biomesmod.json` (text where a number belongs, a cell size of 0, a negative scale) could crash
  or hang world creation. Every value is now checked and held to a working range, with a warning in the log.
* A per-biome table used while planning villages kept the biomes of closed worlds alive on a client.
* The Vostok ice sheet had a ring of bare grass round it.
* Mod metadata: icon, issue tracker and homepage links, an accurate description. The mod is now under the MIT licence.

**Not in this release**
* Signs, hanging signs and boats of the three woods.
* See [KNOWN_ISSUES.md](KNOWN_ISSUES.md) for what is known to be wrong or unverified.

## Earlier

* 0.5.0-beta.1: Kotlin, runtime site engine working on every world preset and on top of terrain mods.
* 0.4.0-beta.1: first site engine, real-place biomes at real elevation.
* 0.3.0: landforms, larger biomes.
* 0.2.0: Overworld and Nether biomes through TerraBlender.
