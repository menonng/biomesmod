package io.github.menonng.biomesmod.terrain.landform

import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.tags.TagKey
import net.minecraft.world.level.biome.Biome

/** `"biomes": ["biomesmod:zhangjiajie", "#minecraft:is_ocean"]`; empty = everywhere. */
data class BiomeFilter(val entries: List<Either<TagKey<Biome>, ResourceKey<Biome>>>) {
	fun everywhere(): Boolean = entries.isEmpty()

	fun test(biome: Holder<Biome>): Boolean =
		entries.isEmpty() || entries.any { e -> e.map({ biome.`is`(it) }, { biome.`is`(it) }) }

	companion object {
		private val ENTRY: Codec<Either<TagKey<Biome>, ResourceKey<Biome>>> =
			Codec.either(TagKey.hashedCodec(Registries.BIOME), ResourceKey.codec(Registries.BIOME))

		@JvmField
		val CODEC: Codec<BiomeFilter> = ENTRY.listOf().xmap({ BiomeFilter(it) }, { it.entries })

		@JvmField
		val ANY = BiomeFilter(listOf())
	}
}
