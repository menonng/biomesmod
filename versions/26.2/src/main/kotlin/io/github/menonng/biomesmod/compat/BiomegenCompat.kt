package io.github.menonng.biomesmod.compat

import net.minecraft.resources.Identifier

/** Version shim for the biome pipeline: 26.2 parses namespaced ids into [Identifier]. */
object BiomegenCompat {
	/** Any "namespace:path" id (vanilla or mod), e.g. "minecraft:forest". */
	fun id(namespacedId: String): Identifier = Identifier.parse(namespacedId)
}
