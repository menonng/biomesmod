package io.github.menonng.biomesmod.terrain

import io.github.menonng.biomesmod.compat.Ids
import io.github.menonng.biomesmod.terrain.field.TerrainFeatures
import io.github.menonng.biomesmod.terrain.landform.LandformFeatures
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration

/**
 * Registers every custom Feature type. Each engine package owns its own registration object
 * (see docs/architecture.md for file ownership).
 */
object ModFeatures {
	fun register() {
		TerrainFeatures.register()
		LandformFeatures.register()
	}

	/** Registers a feature type under biomesmod:[name]. */
	fun <C : FeatureConfiguration, F : Feature<C>> register(name: String, feature: F): F =
		Registry.register(BuiltInRegistries.FEATURE, Ids.id(name), feature)
}
