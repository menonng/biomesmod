package io.github.menonng.biomesmod.worldgen

import com.mojang.datafixers.util.Pair
import io.github.menonng.biomesmod.ModBiomes
import io.github.menonng.biomesmod.compat.BiomegenCompat
import net.minecraft.core.Registry
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Climate
import terrablender.api.Region
import terrablender.api.RegionType
import java.util.function.Consumer

/**
 * One generated overworld region: the vanilla overworld layout with some vanilla biomes swapped for mod biomes.
 * A swapped biome keeps the vanilla climate slot, so its terrain "feel" comes from the slot it takes
 * (spec placement.replace). The generator packs biomes so that no slot is replaced twice in one region.
 *
 * [carriers]: site-placed biomes to add at an unreachable climate point, so they are part of the biome source's
 * possible biomes (feature decoration, locate) while the climate lookup never returns them; the site mixin places
 * them.
 */
class ModOverworldRegion(data: Placements.OverworldRegion, private val carriers: List<String>) :
	Region(BiomegenCompat.id(data.name), RegionType.OVERWORLD, data.weight) {

	private val replacements: Map<ResourceKey<Biome>, ResourceKey<Biome>> =
		data.replace.entries.associate { ModBiomes.key(it.key) to ModBiomes.key(it.value) }

	override fun addBiomes(registry: Registry<Biome>, mapper: Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>>) {
		addModifiedVanillaOverworldBiomes(mapper) { builder -> replacements.forEach { (vanilla, mod) -> builder.replaceBiome(vanilla, mod) } }
		val far = Climate.Parameter.point(9.0f)
		for (id in carriers) {
			mapper.accept(Pair.of(Climate.parameters(far, far, far, far, far, far, 0.0f), ModBiomes.key(id)))
		}
	}
}
