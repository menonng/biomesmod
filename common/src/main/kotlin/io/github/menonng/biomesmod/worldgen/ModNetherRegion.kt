package io.github.menonng.biomesmod.worldgen

import com.mojang.datafixers.util.Pair
import io.github.menonng.biomesmod.ModBiomes
import io.github.menonng.biomesmod.compat.BiomegenCompat
import net.minecraft.core.Registry
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Climate
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList
import terrablender.api.Region
import terrablender.api.RegionType
import java.util.function.Consumer

/** One generated nether region: the vanilla nether points plus mod biome points, so mod regions still blend with vanilla ones. */
class ModNetherRegion(private val data: Placements.NetherRegion) :
	Region(BiomegenCompat.id(data.name), RegionType.NETHER, data.weight) {

	override fun addBiomes(registry: Registry<Biome>, mapper: Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>>) {
		if (data.vanillaPoints) {
			// Taken from the game itself (MultiNoiseBiomeSourceParameterList preset "nether"), not hard-coded.
			MultiNoiseBiomeSourceParameterList.knownPresets()[MultiNoiseBiomeSourceParameterList.Preset.NETHER]!!.values()
				.forEach { mapper.accept(it) }
		}
		for (p in data.points) {
			addBiome(mapper, Climate.parameters(p.temperature, p.humidity, p.continentalness, p.erosion, p.depth, p.weirdness, p.offset),
				ModBiomes.key(p.biome))
		}
	}
}
