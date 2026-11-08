package io.github.menonng.biomesmod.worldgen

import io.github.menonng.biomesmod.BiomesMod
import io.github.menonng.biomesmod.ModBiomes
import io.github.menonng.biomesmod.ModBlocks
import io.github.menonng.biomesmod.compat.SurfaceRulesCompat
import terrablender.api.EndBiomeRegistry
import terrablender.api.Regions
import terrablender.api.TerraBlenderApi

/**
 * "terrablender" entrypoint: biome placement for every dimension, all from the generated placements.json.
 * The End also goes through TerraBlender: its TheEndBiomeSource mixin cancels getNoiseBiome, so Fabric API's
 * TheEndBiomes registrations would never generate while TerraBlender is installed.
 */
class TerraBlenderEntry : TerraBlenderApi {
	override fun onTerraBlenderInitialized() {
		val placements = Placements.get()
		val carriers = placements.sites.allBiomes()
		placements.overworldRegions.forEachIndexed { i, region ->
			Regions.register(ModOverworldRegion(region, if (i == 0) carriers else listOf()))
		}
		placements.netherRegions.forEach { Regions.register(ModNetherRegion(it)) }
		for (e in placements.end) {
			// Vanilla End biomes default to weight 10 each (TerraBlender config).
			val key = ModBiomes.key(e.biome)
			when (e.type) {
				"highlands" -> EndBiomeRegistry.registerHighlandsBiome(key, e.weight)
				"midlands" -> EndBiomeRegistry.registerMidlandsBiome(key, e.weight)
				"edge" -> EndBiomeRegistry.registerEdgeBiome(key, e.weight)
				"islands" -> EndBiomeRegistry.registerIslandBiome(key, e.weight)
				else -> throw IllegalStateException("Unknown End biome type ${e.type} in ${Placements.RESOURCE}")
			}
		}
		// Surface rules may name mod blocks; make sure they are registered even if this entrypoint runs first.
		ModBlocks.ALL.size
		SurfaceRulesCompat.register(placements)
		BiomesMod.LOGGER.info("TerraBlender: {} overworld region(s), {} nether region(s), {} End biome(s); {} site biome(s)",
			placements.overworldRegions.size, placements.netherRegions.size, placements.end.size, carriers.size)
	}
}
