package io.github.menonng.biomesmod.compat;

import com.mojang.serialization.Codec;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Version shim: the codec for a density function field (inline or a reference to a named one). */
public final class DensityCompat {
	/** 1.21.1: {@code DensityFunction.HOLDER_HELPER_CODEC}. */
	public static final Codec<DensityFunction> FUNCTION = DensityFunction.HOLDER_HELPER_CODEC;

	private DensityCompat() {
	}
}
