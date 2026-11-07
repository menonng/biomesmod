package io.github.menonng.biomesmod.mixin

import com.llamalad7.mixinextras.injector.ModifyExpressionValue
import com.llamalad7.mixinextras.sugar.Local
import io.github.menonng.biomesmod.site.SiteRouter
import net.minecraft.core.HolderGetter
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings
import net.minecraft.world.level.levelgen.NoiseRouter
import net.minecraft.world.level.levelgen.RandomState
import net.minecraft.world.level.levelgen.synth.NormalNoise
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At

/**
 * Site terrain: the router of an overworld-like dimension gets the site functions around its final density, depth and
 * preliminary surface just before the random state wires the noises, whatever preset or terrain mod built it.
 */
@Mixin(RandomState::class)
abstract class RandomStateMixin {
	@ModifyExpressionValue(
		method = ["<init>"],
		at = [At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseGeneratorSettings;noiseRouter()Lnet/minecraft/world/level/levelgen/NoiseRouter;")],
	)
	private fun `biomesmod$siteRouter`(
		router: NoiseRouter,
		@Local(argsOnly = true) settings: NoiseGeneratorSettings,
		@Local(argsOnly = true) noises: HolderGetter<NormalNoise.NoiseParameters>,
	): NoiseRouter = SiteRouter.wrap(router, settings, noises)
}
