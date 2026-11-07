package io.github.menonng.biomesmod.terrain.field

/**
 * World-specific instance of a mode (noise seeded from the world seed). Created once per configured feature and
 * world, then shared by all worldgen threads, so implementations must be immutable after construction.
 */
abstract class Shaper protected constructor(seed: Long) {
	enum class WaterPolicy {
		/** Fully reshaped columns (edge factor >= 0.5) lose their original water; the mode owns all water there. */
		REPLACE,

		/** Original rivers, lakes and sea stay wherever the new ground is still below their surface. */
		KEEP,
	}

	/** The mode's world-seeded noise (also used by [ChunkFrame] for surface dressing). */
	@JvmField
	internal val noise = Noise(seed)

	/** Extra ring of columns around the chunk the mode needs to look at (neighbour tests). Max 8. */
	open fun margin(): Int = 2

	/** Edge distance (blocks) the mode reads through [ChunkFrame.edgeDistance], beyond edge_blend. */
	open fun edgeReach(): Int = 0

	open fun waterPolicy(): WaterPolicy = WaterPolicy.REPLACE

	/**
	 * True when the mode defines the whole surface of its biome (dunes, salt pans...): every column inside the fade
	 * gets the mode palette. False when it only changes some columns (a canyon leaves its plateau alone).
	 */
	open fun fullSurface(): Boolean = true

	open fun defaultPalette(): FieldPalette = FieldPalette.EMPTY

	/** Drop (blocks) to the lowest neighbour from which a column counts as a cliff face, unless the palette says. */
	open fun defaultCliffSlope(): Int = 1024

	/** Per-chunk precomputation over the frame grid (called after the edge field and original columns are read). */
	open fun prepare(frame: ChunkFrame) {
	}

	/** Fill [out] for world column (x, z) at frame grid index i. */
	abstract fun column(frame: ChunkFrame, x: Int, z: Int, i: Int, out: Column)
}
