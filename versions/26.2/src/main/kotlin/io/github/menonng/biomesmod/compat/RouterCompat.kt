package io.github.menonng.biomesmod.compat

import io.github.menonng.biomesmod.site.SiteDensity
import net.minecraft.world.level.levelgen.DensityFunction
import net.minecraft.world.level.levelgen.NoiseRouter

/** Version shim: 26.2 routers carry `preliminarySurfaceLevel` (a 2D height) for the preliminary surface. */
object RouterCompat {
	val SURFACE_MODE = SiteDensity.Mode.SURFACE

	fun rebuild(r: NoiseRouter, depth: DensityFunction, finalDensity: DensityFunction, surface: (DensityFunction) -> DensityFunction): NoiseRouter =
		NoiseRouter(r.barrierNoise(), r.fluidLevelFloodednessNoise(), r.fluidLevelSpreadNoise(), r.lavaNoise(), r.temperature(), r.vegetation(),
			r.continents(), r.erosion(), depth, r.ridges(), surface(r.preliminarySurfaceLevel()), finalDensity, r.veinToggle(),
			r.veinRidged(), r.veinGap())
}
