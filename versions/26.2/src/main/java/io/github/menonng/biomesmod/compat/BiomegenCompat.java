package io.github.menonng.biomesmod.compat;

import net.minecraft.resources.Identifier;

/** Version shim for the biome pipeline: 26.2 parses namespaced ids into {@code Identifier}. */
public final class BiomegenCompat {
	private BiomegenCompat() {
	}

	/** Any "namespace:path" id (vanilla or mod), e.g. "minecraft:forest". */
	public static Identifier id(String namespacedId) {
		return Identifier.parse(namespacedId);
	}
}
