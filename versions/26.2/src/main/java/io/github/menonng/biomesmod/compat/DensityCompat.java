package io.github.menonng.biomesmod.compat;

import com.mojang.serialization.Codec;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Version shim: the codec for a density function field (inline or a reference to a named one). */
public final class DensityCompat {
	/** 26.2: {@code DensityFunction.CODEC} already resolves references. */
	public static final Codec<DensityFunction> FUNCTION = DensityFunction.CODEC;

	private DensityCompat() {
	}
}
