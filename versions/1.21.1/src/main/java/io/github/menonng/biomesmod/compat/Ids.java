package io.github.menonng.biomesmod.compat;

import io.github.menonng.biomesmod.BiomesMod;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/** Version shim: 1.21.1 uses {@code ResourceLocation} for namespaced ids. */
public final class Ids {
	private Ids() {
	}

	public static ResourceLocation id(String path) {
		return ResourceLocation.fromNamespaceAndPath(BiomesMod.MOD_ID, path);
	}

	/** Plain "namespace:path" of a key (ResourceKey#location() in this version). */
	public static String name(ResourceKey<?> key) {
		return key.location().toString();
	}
}
