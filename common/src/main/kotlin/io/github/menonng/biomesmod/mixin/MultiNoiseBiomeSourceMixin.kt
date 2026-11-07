package io.github.menonng.biomesmod.mixin

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import io.github.menonng.biomesmod.site.SiteBiomes
import net.minecraft.core.Holder
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.biome.Climate
import net.minecraft.world.level.biome.MultiNoiseBiomeSource
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Unique

/**
 * Site biomes: inside a site the site's biome wins over the climate lookup. Wraps the whole method because
 * TerraBlender replaces its result from a HEAD injector; a wrapper runs outside every injector.
 */
@Mixin(MultiNoiseBiomeSource::class)
abstract class MultiNoiseBiomeSourceMixin {
	@Unique
	@Volatile
	private var `biomesmod$holders`: Map<String, Holder<Biome>>? = null

	@WrapMethod(method = ["getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate\$Sampler;)Lnet/minecraft/core/Holder;"])
	private fun `biomesmod$siteBiome`(x: Int, y: Int, z: Int, sampler: Climate.Sampler, original: Operation<Holder<Biome>>): Holder<Biome> {
		val holders = `biomesmod$holders` ?: SiteBiomes.holders(this as Any as BiomeSource).also { `biomesmod$holders` = it }
		return SiteBiomes.override(holders, x, y, z, sampler) ?: original.call(x, y, z, sampler)
	}
}
