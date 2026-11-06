package io.github.menonng.biomesmod.terrain.field;

import net.minecraft.world.level.block.state.BlockState;

/**
 * What a mode wants for one column. Modes fill it from pure functions of seed + coordinates; the only impure
 * input they may use is the column's original ground ({@link ChunkFrame#orig}), and only for {@link #ground}.
 * {@link #water} must never depend on it: neighbouring chunks predict each other's water from it.
 */
public final class Column {
	public static final int NONE = Integer.MIN_VALUE;
	/** This column carries a waterfall from {@link #fallTop} down to its own water or ground. */
	public static final int FALL = 1;
	/** Never raise this column to hold back neighbouring water (waterfall columns). */
	public static final int NO_WALL = 2;
	/** Dress the surface with the mode palette even where the edge fade would keep the original surface. */
	public static final int FORCE_DRESS = 4;

	/** Target top-of-ground Y (fractional; blended with the original ground by the edge factor). */
	public double ground;
	/** Water level in Minecraft's sea-level convention (water fills Y < water), or NONE. Pure. */
	public int water;
	/** Surface block override (depth {@link #surfaceDepth}), or null for the palette. */
	public BlockState surface;
	public int surfaceDepth;
	/** Original water below this Y is kept under the new ground (floating ice shelves), or NONE. */
	public int keepWaterBelow;
	/** Explicit blend factor in [0, 1] replacing the edge factor, or -1 to use the edge factor. */
	public double blend;
	/** Lower bound applied after blending (rims and dikes that must hold even in the fade band). */
	public double minGround;
	public int flags;
	/** Top Y of the waterfall for FALL columns. */
	public int fallTop;

	public void reset(double originalGround) {
		ground = originalGround;
		water = NONE;
		surface = null;
		surfaceDepth = 1;
		keepWaterBelow = NONE;
		blend = -1;
		minGround = Double.NEGATIVE_INFINITY;
		flags = 0;
		fallTop = NONE;
	}
}
