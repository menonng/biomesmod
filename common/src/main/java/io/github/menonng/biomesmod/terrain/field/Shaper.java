package io.github.menonng.biomesmod.terrain.field;

/**
 * World-specific instance of a mode (noise seeded from the world seed). Created once per configured feature and
 * world, then shared by all worldgen threads, so implementations must be immutable after construction.
 */
public abstract class Shaper {
	public enum WaterPolicy {
		/** Fully reshaped columns (edge factor >= 0.5) lose their original water; the mode owns all water there. */
		REPLACE,
		/** Original rivers, lakes and sea stay wherever the new ground is still below their surface. */
		KEEP
	}

	protected final Noise noise;

	protected Shaper(long seed) {
		this.noise = new Noise(seed);
	}

	/** Extra ring of columns around the chunk the mode needs to look at (neighbour tests). Max 8. */
	public int margin() {
		return 2;
	}

	/** Edge distance (blocks) the mode reads through {@link ChunkFrame#edgeDistance}, beyond edge_blend. */
	public int edgeReach() {
		return 0;
	}

	public WaterPolicy waterPolicy() {
		return WaterPolicy.REPLACE;
	}

	/**
	 * True when the mode defines the whole surface of its biome (dunes, salt pans...): every column inside the fade
	 * gets the mode palette. False when it only changes some columns (a canyon leaves its plateau alone).
	 */
	public boolean fullSurface() {
		return true;
	}

	public FieldPalette defaultPalette() {
		return FieldPalette.EMPTY;
	}

	/** Drop (blocks) to the lowest neighbour from which a column counts as a cliff face, unless the palette says. */
	public int defaultCliffSlope() {
		return 1024;
	}

	/** Per-chunk precomputation over the frame grid (called after the edge field and original columns are read). */
	public void prepare(ChunkFrame frame) {
	}

	/** Fill {@code out} for world column (x, z) at frame grid index i. */
	public abstract void column(ChunkFrame frame, int x, int z, int i, Column out);
}
