# Known issues and limits (0.6.0)

What is known to be wrong, unfinished or unverified. "Checked" below always means the headless self-check on Minecraft
26.2, seed 0, and top-down or isometric renders made by it. **Nobody has played this version in a client as part of
the release check.**

## Not verified

* **In a client.** Block models, item models, the creative tabs, the recipe book, door and trapdoor rendering, the
  textures of the new wood blocks in the world, shader packs. The server loads every blockstate-independent file
  (recipes, loot tables, tags, advancements) without an error; models and blockstates are only read by a client.
* **Minecraft 1.21.1.** The jar builds, and its recipes are written in that version's format, but it has not been run
  since 0.6.0-beta.14. Villages, beaches, the site rules and every block added since are untested there.
* **Other worldgen mods.** Last measured at 0.5.0-beta.1 (see the README). The village planning mixins added since
  (`JigsawStructure.findGenerationPoint`, `NoiseBasedChunkGenerator.getBaseHeight`) have not been run next to them.
* **Large Biomes and Amplified.** Last checked at 0.5.0-beta.1. The site rules, villages and beaches added since have
  only been run on the Default preset.
* **Performance.** Chunk generation speed has not been measured against vanilla for this version. The full self-check
  (64 biomes, about 35 minutes) completes without watchdog trouble; that is all that is known.
* **Multiplayer.** A dedicated server is what the self-check runs, so server start and worldgen are covered. A client
  joining it is not.
* **Existing worlds.** Terrain rules changed in nearly every beta. A world made with an older beta gets seams at the
  edge of what was already generated. There is no migration.

## Known defects

* A village can be cut by a terrace or cliff step that its planning did not foresee (seen once, in Pamukkale; left as is).
* Pale grass rings: sites whose biome has no snow or sand surface can show a thin ring of grass at the edge of their
  shaped ground (seen round Dallol; fixed for Vostok only).
* Lençóis Maranhenses can end in a straight seam; the McMurdo Dry Valleys can end in a steep step.
* Coasts without a beach biome, and the line where a forest meets the sea, are as in vanilla: the wide flat beach only
  exists inside beach biomes.
* Temperate sites are not matched to their neighbours: a canyon can stand next to a forest.
* The village report of the self-check over-counts buried and floating houses; renders are the reference.
* The `canyon` shaper with a raised plateau (`plateau` > 0) does not move the height maps with the ground, so trees and
  plants placed by height map fail on the raised part (the Grand Canyon rim is barer than intended).
* Waterfalls longer than about 40 blocks end early and are finished by vanilla fluid ticks.

## By design

* `/biomesmod sites [biome]` needs no permission. It reads the seed-determined layout, like a seed map would; server
  owners who mind should block it with a permissions mod.
* The config only shapes new chunks. Changing it on a live world gives seams.
* The mod overrides vanilla's `continents` density function and `canyon` carver. Another pack that replaces them changes
  the coasts and ravines, not the sites.
* The self-check and the map renderer are in the release jar. They only run with `-Dbiomesmod.selfcheck=true` or
  `-Dbiomesmod.maps=...`.
* No signs, hanging signs or boats for the three woods.
* The satellite images in `docs/biomes/img` are third-party works under their own licences (credited on each biome page); the MIT licence covers the mod and its own images.
