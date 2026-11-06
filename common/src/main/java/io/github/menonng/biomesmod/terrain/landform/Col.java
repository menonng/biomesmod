package io.github.menonng.biomesmod.terrain.landform;

import net.minecraft.world.level.block.state.BlockState;

/**
 * The outcome of a landform for one column, computed by a pure function of (instance, x, z).
 * {@link ChunkWriter#apply} turns it into blocks.
 */
final class Col {
	static final int NONE = Integer.MIN_VALUE;

	/** Wanted terrain top (inclusive), or NONE for "leave the column alone". Raises always; lowers only if {@link #carve}. */
	int top = NONE;
	/** Allow lowering the existing terrain to {@link #top}. */
	boolean carve;
	/** Repaint this many surface blocks with the material even if the height does not change. */
	int paint;
	/** Fluid surface Y to fill above the top, or NONE. */
	int water = NONE;
	/** Fluid to fill with (null = water). */
	BlockState fluid;
	/** Shape-defined material kind passed to {@link Landform#block}. */
	int kind;
	/** The top is a vegetated cap (cap block, cap feature). */
	boolean cap;
	/** Optional cut through the column after filling (arches, wave notches): [voidLo, voidHi]. */
	int voidLo = NONE;
	int voidHi = NONE;
	/** Inside the cut, Y at or below this becomes water (sea level), above it air. */
	int voidWater = NONE;
	/** Shape-defined flags (falls, vents, no-seal ...). */
	int flag;
	/** Shape-defined extra value. */
	int aux;

	static final int FLAG_NO_SEAL = 1;

	boolean isWater() {
		return water != NONE && top != NONE && water > top;
	}
}
