package io.github.menonng.biomesmod.terrain.landform

import io.github.menonng.biomesmod.terrain.ModFeatures

/** Registers the grid-placed landform feature and the umbrella tree. */
object LandformFeatures {
	@JvmField
	val LANDFORM = LandformFeature()

	@JvmField
	val UMBRELLA_TREE = UmbrellaTreeFeature()

	fun register() {
		ModFeatures.register("landform", LANDFORM)
		ModFeatures.register("umbrella_tree", UMBRELLA_TREE)
	}
}
