package io.github.menonng.biomesmod.worldgen;

import io.github.menonng.biomesmod.ModBiomes;
import io.github.menonng.biomesmod.compat.SurfaceRulesCompat;
import terrablender.api.EndBiomeRegistry;
import terrablender.api.Regions;
import terrablender.api.TerraBlenderApi;

/**
 * "terrablender" entrypoint: biome placement for every dimension.
 * The End also goes through TerraBlender: its TheEndBiomeSource mixin cancels getNoiseBiome,
 * so Fabric API's TheEndBiomes registrations would never generate while TerraBlender is installed.
 */
public final class TerraBlenderEntry implements TerraBlenderApi {
	@Override
	public void onTerraBlenderInitialized() {
		Regions.register(new ModOverworldRegion());
		Regions.register(new ModNetherRegion());

		// Vanilla end_highlands defaults to weight 10 (TerraBlender config); 5 gives roughly 1/3 of highlands.
		EndBiomeRegistry.registerHighlandsBiome(ModBiomes.CHORUS_THICKET, 5);

		SurfaceRulesCompat.register();
	}
}
