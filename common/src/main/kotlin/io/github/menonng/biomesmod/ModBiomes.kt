package io.github.menonng.biomesmod

import io.github.menonng.biomesmod.compat.BiomegenCompat
import io.github.menonng.biomesmod.worldgen.Placements
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome

/**
 * All mod biomes and the dimension each one generates in, read from the generated placement data
 * (common/src/main/resources/biomesmod/placements.json, written by tools/biomegen from specs/).
 * Biome definitions are generated too (data/biomesmod/worldgen/biome/\*.json, one set per version).
 * Placement for all dimensions is registered in [io.github.menonng.biomesmod.worldgen.TerraBlenderEntry].
 */
object ModBiomes {
	data class Entry(val key: ResourceKey<Biome>, val dimension: ResourceKey<Level>)

	/** Every mod biome, overworld first, then nether, then End (each sorted by id). */
	@JvmField
	val ALL: List<Entry> = Placements.get().biomes.map { Entry(key(it.id), level(it.dimension)) }

	/** Biome key from a namespaced id such as "biomesmod:gotjawal" or "minecraft:forest". */
	fun key(id: String): ResourceKey<Biome> = ResourceKey.create(Registries.BIOME, BiomegenCompat.id(id))

	private fun level(id: String): ResourceKey<Level> = when (id) {
		"minecraft:overworld" -> Level.OVERWORLD
		"minecraft:the_nether" -> Level.NETHER
		"minecraft:the_end" -> Level.END
		else -> throw IllegalStateException("Unknown dimension $id in ${Placements.RESOURCE}")
	}
}
