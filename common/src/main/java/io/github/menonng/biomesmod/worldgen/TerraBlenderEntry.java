package io.github.menonng.biomesmod.worldgen;

import io.github.menonng.biomesmod.BiomesMod;
import io.github.menonng.biomesmod.ModBiomes;
import io.github.menonng.biomesmod.compat.SurfaceRulesCompat;
import java.util.List;
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
		List<String> carriers = placements.sites().specs().stream().map(s -> s.biome()).toList();
		for (int i = 0; i < placements.overworldRegions().size(); i++) {
			Regions.register(new ModOverworldRegion(placements.overworldRegions().get(i), i == 0 ? carriers : List.of()));
		}
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
		// Surface rules may name mod blocks; make sure they are registered even if this entrypoint runs first.
		io.github.menonng.biomesmod.ModBlocks.ALL.size();
		SurfaceRulesCompat.register(placements);
		BiomesMod.LOGGER.info("TerraBlender: {} overworld region(s), {} nether region(s), {} End biome(s); {} site biome(s)",
				placements.overworldRegions().size(), placements.netherRegions().size(), placements.end().size(), carriers.size());
	}
}
