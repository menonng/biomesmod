package io.github.menonng.biomesmod.compat

import net.minecraft.world.level.levelgen.DensityFunction

/**
 * Version shim for this mod's own density functions. The visitor API differs: 1.21.1 has an abstract `mapAll`
 * (the function maps its children and then itself), 26.2 an abstract `mapChildren` (the visitor recurses).
 * Subclasses only say how to rebuild themselves from mapped children.
 */
abstract class DensityBase : DensityFunction {
	/** A copy of this function with every child function and noise passed through the mappers. */
	protected abstract fun rebuild(
		function: (DensityFunction) -> DensityFunction,
		noise: (DensityFunction.NoiseHolder) -> DensityFunction.NoiseHolder,
	): DensityFunction

	override fun mapAll(visitor: DensityFunction.Visitor): DensityFunction =
		visitor.apply(rebuild({ it.mapAll(visitor) }, { visitor.visitNoise(it) }))
}
