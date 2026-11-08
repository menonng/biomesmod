package io.github.menonng.biomesmod.terrain.landform

import net.minecraft.world.level.block.state.BlockState

/**
 * The outcome of a landform for one column, computed by a pure function of (instance, x, z). [ChunkWriter.apply]
 * turns it into blocks.
 */
internal class Col {
	/** Wanted terrain top (inclusive), or NONE for "leave the column alone". Raises always; lowers only if [carve]. */
	@JvmField
	var top = NONE

	/** Allow lowering the existing terrain to [top]. */
	@JvmField
	var carve = false

	/** Repaint this many surface blocks with the material even if the height does not change. */
	@JvmField
	var paint = 0

	/** Fluid surface Y to fill above the top, or NONE. */
	@JvmField
	var water = NONE

	/** Fluid to fill with (null = water). */
	@JvmField
	var fluid: BlockState? = null

	/** Shape-defined material kind passed to [Landform.block]. */
	@JvmField
	var kind = 0

	/** The top is a vegetated cap (cap block, cap feature). */
	@JvmField
	var cap = false

	/** Optional cut through the column after filling (arches, wave notches): [voidLo, voidHi]. */
	@JvmField
	var voidLo = NONE

	@JvmField
	var voidHi = NONE

	/** Inside the cut, Y at or below this becomes water (sea level), above it air. */
	@JvmField
	var voidWater = NONE

	/** Shape-defined flags (falls, vents, no-seal ...). */
	@JvmField
	var flag = 0

	/** Shape-defined extra value. */
	@JvmField
	var aux = 0

	/**
	 * Optional air gaps inside a raised column, ascending [lo0, hi0, lo1, hi1, ...] (first [gapCount] pairs): overhangs,
	 * undercuts and the space under a leaning spire. Only blocks above the existing terrain are affected.
	 */
	@JvmField
	var gaps: IntArray? = null

	@JvmField
	var gapCount = 0

	/** While [ChunkWriter.apply] writes, the top of the solid span being written (== [top] for the main span). */
	@JvmField
	var span = NONE

	fun isWater(): Boolean = water != NONE && top != NONE && water > top

	fun addGap(lo: Int, hi: Int) {
		if (hi < lo) {
			return
		}
		var g = gaps
		if (g == null) {
			g = IntArray(8)
			gaps = g
		} else if (gapCount * 2 + 2 > g.size) {
			g = g.copyOf(g.size * 2)
			gaps = g
		}
		g[gapCount * 2] = lo
		g[gapCount * 2 + 1] = hi
		gapCount++
	}

	/** True if y is inside the raised body (at or below [top] and not in a gap). */
	fun solidAt(y: Int): Boolean {
		if (top == NONE || y > top) {
			return false
		}
		val g = gaps ?: return true
		for (k in 0 until gapCount) {
			if (y >= g[k * 2] && y <= g[k * 2 + 1]) {
				return false
			}
		}
		return true
	}

	companion object {
		const val NONE = Int.MIN_VALUE
		const val FLAG_NO_SEAL = 1
	}
}
