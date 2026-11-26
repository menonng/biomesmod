package io.github.menonng.biomesmod.compat

import io.github.menonng.biomesmod.BiomesMod
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation

/** Version shim: 1.20.1 uses [ResourceLocation] for namespaced ids. */
object Ids {
	fun id(path: String): ResourceLocation = ResourceLocation(BiomesMod.MOD_ID, path)

	/** Plain "namespace:path" of a key (ResourceKey#location() in this version). */
	fun name(key: ResourceKey<*>): String = key.location().toString()
}
