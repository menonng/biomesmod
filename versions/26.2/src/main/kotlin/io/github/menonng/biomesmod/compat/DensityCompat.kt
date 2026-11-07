package io.github.menonng.biomesmod.compat

import com.mojang.serialization.Codec
import net.minecraft.world.level.levelgen.DensityFunction

/** Version shim: the codec for a density function field (inline or a reference to a named one). */
object DensityCompat {
	/** 26.2: `DensityFunction.CODEC` already resolves references. */
	val FUNCTION: Codec<DensityFunction> = DensityFunction.CODEC
}
