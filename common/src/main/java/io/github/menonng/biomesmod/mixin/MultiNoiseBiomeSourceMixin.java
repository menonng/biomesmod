package io.github.menonng.biomesmod.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.github.menonng.biomesmod.site.SiteBiomes;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * Site biomes: inside a site the site's biome wins over the climate lookup. Wraps the whole method because
 * TerraBlender replaces its result from a HEAD injector; a wrapper runs outside every injector.
 */
@Mixin(MultiNoiseBiomeSource.class)
abstract class MultiNoiseBiomeSourceMixin {
	@Unique
	private volatile Map<String, Holder<Biome>> biomesmod$holders;

	@WrapMethod(method = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;")
	private Holder<Biome> biomesmod$siteBiome(int x, int y, int z, Climate.Sampler sampler, Operation<Holder<Biome>> original) {
		Map<String, Holder<Biome>> holders = biomesmod$holders;
		if (holders == null) {
			holders = SiteBiomes.holders((BiomeSource) (Object) this);
			biomesmod$holders = holders;
		}
		Holder<Biome> site = SiteBiomes.override(holders, x, y, z, sampler);
		return site != null ? site : original.call(x, y, z, sampler);
	}
}
