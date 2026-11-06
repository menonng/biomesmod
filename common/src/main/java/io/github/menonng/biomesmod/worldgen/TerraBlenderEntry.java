package io.github.menonng.biomesmod.worldgen;

import io.github.menonng.biomesmod.BiomesMod;
import io.github.menonng.biomesmod.ModBiomes;
import io.github.menonng.biomesmod.compat.SurfaceRulesCompat;
import terrablender.api.EndBiomeRegistry;
import terrablender.api.Regions;
import terrablender.api.TerraBlenderApi;

/**
 * "terrablender" entrypoint: biome placement for every dimension, all from the generated placements.json.
 * The End also goes through TerraBlender: its TheEndBiomeSource mixin cancels getNoiseBiome,
 * so Fabric API's TheEndBiomes registrations would never generate while TerraBlender is installed.
 */
public final class TerraBlenderEntry implements TerraBlenderApi {
	@Override
	public void onTerraBlenderInitialized() {
		Placements placements = Placements.get();
		placements.overworldRegions().forEach(r -> Regions.register(new ModOverworldRegion(r)));
		placements.netherRegions().forEach(r -> Regions.register(new ModNetherRegion(r)));
		for (Placements.EndEntry e : placements.end()) {
			// Vanilla End biomes default to weight 10 each (TerraBlender config).
			switch (e.type()) {
				case "highlands" -> EndBiomeRegistry.registerHighlandsBiome(ModBiomes.key(e.biome()), e.weight());
				case "midlands" -> EndBiomeRegistry.registerMidlandsBiome(ModBiomes.key(e.biome()), e.weight());
				case "edge" -> EndBiomeRegistry.registerEdgeBiome(ModBiomes.key(e.biome()), e.weight());
				case "islands" -> EndBiomeRegistry.registerIslandBiome(ModBiomes.key(e.biome()), e.weight());
				default -> throw new IllegalStateException("Unknown End biome type " + e.type() + " in " + Placements.RESOURCE);
			}
		}
		SurfaceRulesCompat.register(placements);
		BiomesMod.LOGGER.info("TerraBlender: {} overworld region(s), {} nether region(s), {} End biome(s)",
				placements.overworldRegions().size(), placements.netherRegions().size(), placements.end().size());
	}
}
