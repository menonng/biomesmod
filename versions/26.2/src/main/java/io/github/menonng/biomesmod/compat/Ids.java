package io.github.menonng.biomesmod.compat;

import io.github.menonng.biomesmod.BiomesMod;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;

/** Version shim: 26.2 uses {@code Identifier} for namespaced ids. */
public final class Ids {
	private Ids() {
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(BiomesMod.MOD_ID, path);
	}

	/** Plain "namespace:path" of a key (ResourceKey#identifier() in this version). */
	public static String name(ResourceKey<?> key) {
		return key.identifier().toString();
	}
}
