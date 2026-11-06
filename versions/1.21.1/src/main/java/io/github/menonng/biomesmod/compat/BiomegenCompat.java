package io.github.menonng.biomesmod.compat;

import net.minecraft.resources.ResourceLocation;

/** Version shim for the biome pipeline: 1.21.1 parses namespaced ids into {@code ResourceLocation}. */
public final class BiomegenCompat {
	private BiomegenCompat() {
	}

	/** Any "namespace:path" id (vanilla or mod), e.g. "minecraft:forest". */
	public static ResourceLocation id(String namespacedId) {
		return ResourceLocation.parse(namespacedId);
	}
}
