package io.github.menonng.biomesmod.compat

import com.mojang.serialization.Codec
import net.minecraft.world.level.levelgen.DensityFunction

/** Version shim: the codec for a density function field (inline or a reference to a named one). */
object DensityCompat {
	/** 1.21.1: `DensityFunction.HOLDER_HELPER_CODEC`. */
	val FUNCTION: Codec<DensityFunction> = DensityFunction.HOLDER_HELPER_CODEC
}
