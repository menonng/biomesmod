package io.github.menonng.biomesmod.compat

import io.github.menonng.biomesmod.site.SiteDensity
import net.minecraft.world.level.levelgen.DensityFunction
import net.minecraft.world.level.levelgen.NoiseRouter

/** Version shim: 1.21.1 routers carry `initialDensityWithoutJaggedness` (a 3D density) for the preliminary surface. */
object RouterCompat {
	val SURFACE_MODE = SiteDensity.Mode.INITIAL

	fun rebuild(r: NoiseRouter, depth: DensityFunction, finalDensity: DensityFunction, surface: (DensityFunction) -> DensityFunction): NoiseRouter =
		NoiseRouter(r.barrierNoise(), r.fluidLevelFloodednessNoise(), r.fluidLevelSpreadNoise(), r.lavaNoise(), r.temperature(), r.vegetation(),
			r.continents(), r.erosion(), depth, r.ridges(), surface(r.initialDensityWithoutJaggedness()), finalDensity, r.veinToggle(),
			r.veinRidged(), r.veinGap())
}
