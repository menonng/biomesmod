package io.github.menonng.biomesmod.terrain.field

import net.minecraft.world.level.block.state.BlockState

/**
 * What a mode wants for one column. Modes fill it from pure functions of seed + coordinates; the only impure input
 * they may use is the column's original ground ([ChunkFrame.orig]), and only for [ground]. [water] must never depend
 * on it: neighbouring chunks predict each other's water from it.
 */
class Column {
	/** Target top-of-ground Y (fractional; blended with the original ground by the edge factor). */
	@JvmField
	var ground = 0.0

	/** Water level in Minecraft's sea-level convention (water fills Y < water), or NONE. Pure. */
	@JvmField
	var water = NONE

	/** Surface block override (depth [surfaceDepth]), or null for the palette. */
	@JvmField
	var surface: BlockState? = null

	@JvmField
	var surfaceDepth = 1

	/** Original water below this Y is kept under the new ground (floating ice shelves), or NONE. */
	@JvmField
	var keepWaterBelow = NONE

	/** Explicit blend factor in [0, 1] replacing the edge factor, or -1 to use the edge factor. */
	@JvmField
	var blend = -1.0

	/** Lower bound applied after blending (rims and dikes that must hold even in the fade band). */
	@JvmField
	var minGround = Double.NEGATIVE_INFINITY

	@JvmField
	var flags = 0

	/** Top Y of the waterfall for FALL columns. */
	@JvmField
	var fallTop = NONE

	fun reset(originalGround: Double) {
		ground = originalGround
		water = NONE
		surface = null
		surfaceDepth = 1
		keepWaterBelow = NONE
		blend = -1.0
		minGround = Double.NEGATIVE_INFINITY
		flags = 0
		fallTop = NONE
	}

	companion object {
		const val NONE = Int.MIN_VALUE

		/** This column carries a waterfall from [fallTop] down to its own water or ground. */
		const val FALL = 1

		/** Never raise this column to hold back neighbouring water (waterfall columns). */
		const val NO_WALL = 2

		/** Dress the surface with the mode palette even where the edge fade would keep the original surface. */
		const val FORCE_DRESS = 4
	}
}
