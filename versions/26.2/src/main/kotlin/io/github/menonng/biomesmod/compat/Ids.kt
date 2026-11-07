package io.github.menonng.biomesmod.compat

import io.github.menonng.biomesmod.BiomesMod
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey

/** Version shim: 26.2 uses [Identifier] for namespaced ids. */
object Ids {
	fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(BiomesMod.MOD_ID, path)

	/** Plain "namespace:path" of a key (ResourceKey#identifier() in this version). */
	fun name(key: ResourceKey<*>): String = key.identifier().toString()
}
