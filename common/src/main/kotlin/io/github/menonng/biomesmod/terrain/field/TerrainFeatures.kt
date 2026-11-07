package io.github.menonng.biomesmod.terrain.field

import io.github.menonng.biomesmod.terrain.ModFeatures

/** Registers the column-based terrain shaping feature. */
object TerrainFeatures {
	@JvmField
	val TERRAIN_SHAPER = TerrainShaperFeature(ShaperMode.CODEC)

	fun register() {
		ModFeatures.register("terrain_shaper", TERRAIN_SHAPER)
	}
}
