package io.github.menonng.biomesmod.terrain;

import io.github.menonng.biomesmod.compat.Ids;
import io.github.menonng.biomesmod.terrain.coast.CoastFeatures;
import io.github.menonng.biomesmod.terrain.field.TerrainFeatures;
import io.github.menonng.biomesmod.terrain.landform.LandformFeatures;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

/**
 * Registers every custom Feature type. Each engine package owns its own registration class
 * (see docs/architecture.md for file ownership).
 */
public final class ModFeatures {
	private ModFeatures() {
	}

	public static void register() {
		TerrainFeatures.register();
		LandformFeatures.register();
		CoastFeatures.register();
	}

	/** Registers a feature type under biomesmod:{@code name}. */
	public static <C extends FeatureConfiguration, F extends Feature<C>> F register(String name, F feature) {
		return Registry.register(BuiltInRegistries.FEATURE, Ids.id(name), feature);
	}
}
