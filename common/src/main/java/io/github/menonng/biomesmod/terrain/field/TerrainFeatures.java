package io.github.menonng.biomesmod.terrain.field;

import io.github.menonng.biomesmod.terrain.ModFeatures;

/** Owner: terrain-field engine agent. Registers column-based terrain shaping features. */
public final class TerrainFeatures {
	public static final TerrainShaperFeature TERRAIN_SHAPER = new TerrainShaperFeature(ShaperMode.CODEC);

	private TerrainFeatures() {
	}

	public static void register() {
		ModFeatures.register("terrain_shaper", TERRAIN_SHAPER);
	}
}
