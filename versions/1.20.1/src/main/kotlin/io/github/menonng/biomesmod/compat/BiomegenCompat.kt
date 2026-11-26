package io.github.menonng.biomesmod.compat

import net.minecraft.resources.ResourceLocation

/** Version shim for the biome pipeline: 1.20.1 parses namespaced ids into [ResourceLocation]. */
object BiomegenCompat {
	/** Any "namespace:path" id (vanilla or mod), e.g. "minecraft:forest". */
	fun id(namespacedId: String): ResourceLocation = ResourceLocation(namespacedId)
}
