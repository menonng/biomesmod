package io.github.menonng.biomesmod.site

import io.github.menonng.biomesmod.BiomesConfig
import io.github.menonng.biomesmod.BiomesMod
import io.github.menonng.biomesmod.compat.Ids
import io.github.menonng.biomesmod.compat.RouterCompat
import io.github.menonng.biomesmod.worldgen.Placements
import net.minecraft.core.HolderGetter
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.levelgen.DensityFunction
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings
import net.minecraft.world.level.levelgen.NoiseRouter
import net.minecraft.world.level.levelgen.synth.NormalNoise

/**
 * Installs the sites into a noise router before the random state wires it (see `RandomStateMixin`). Only routers of
 * overworld-like dimensions are touched (a world floor below 0 and a real sea), whatever their density functions are:
 * the vanilla presets, Large Biomes, Amplified, or a terrain mod's own router.
 */
object SiteRouter {
	fun wrap(router: NoiseRouter, settings: NoiseGeneratorSettings, noises: HolderGetter<NormalNoise.NoiseParameters>): NoiseRouter {
		if (Placements.get().sites.specs.isEmpty() || settings.noiseSettings().minY() >= 0 || settings.seaLevel() < 32) {
			return router
		}
		if (contains(router.depth()) { it is SiteDensity }) {
			return router // already installed (a data pack shipping old-style wrappers)
		}
		val noise = noises.get(ResourceKey.create(Registries.NOISE, Ids.id("site"))).orElse(null) ?: return router
		val holder = DensityFunction.NoiseHolder(noise)
		val large = usesNoise(router.temperature(), "_large") || usesNoise(router.continents(), "_large")
		val scale = if (large) BiomesConfig.sites.largeBiomesScale else 1.0
		val depth = router.depth()
		fun node(mode: SiteDensity.Mode, argument: DensityFunction) = SiteDensity(mode, argument, depth, holder, router.temperature(),
			router.vegetation(), router.continents(), router.erosion(), scale)
		BiomesMod.LOGGER.debug("Installing sites into a noise router (scale {})", scale)
		return RouterCompat.rebuild(router, node(SiteDensity.Mode.DEPTH, depth), node(SiteDensity.Mode.FINAL, router.finalDensity())) {
			node(RouterCompat.SURFACE_MODE, it)
		}
	}

	private fun contains(function: DensityFunction, test: (DensityFunction) -> Boolean): Boolean {
		var found = false
		function.mapAll { f ->
			if (test(f)) {
				found = true
			}
			f
		}
		return found
	}

	/** True when the function samples a noise whose id ends with [suffix] (e.g. minecraft:temperature_large). */
	private fun usesNoise(function: DensityFunction, suffix: String): Boolean {
		var found = false
		function.mapAll(object : DensityFunction.Visitor {
			override fun apply(f: DensityFunction): DensityFunction = f

			override fun visitNoise(noise: DensityFunction.NoiseHolder): DensityFunction.NoiseHolder {
				if (noise.noiseData().unwrapKey().map { Ids.name(it).endsWith(suffix) }.orElse(false)) {
					found = true
				}
				return noise
			}
		})
		return found
	}
}
