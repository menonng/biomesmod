package io.github.menonng.biomesmod.terrain.landform

import io.github.menonng.biomesmod.terrain.util.StrataPalette
import net.minecraft.world.level.block.state.BlockState
import java.util.Optional

/**
 * A landform shape: its JSON parameters plus a factory for instances. One subclass per `"shape"` value. Instances must
 * be a pure function of the [Site] (world seed, cell, noise-only terrain).
 */
abstract class LandformShape {
	/** The `"shape"` value. */
	abstract fun name(): String

	/**
	 * Upper bound, in blocks from the instance centre, of anything an instance of this config may write. Used to find
	 * instances that overlap a chunk.
	 */
	internal abstract fun maxReach(): Int

	/** Creates the instance at the site's centre, or returns null if the terrain does not suit the shape. */
	internal abstract fun create(site: Site): Landform?

	internal abstract fun defaultPalette(): StrataPalette

	internal open fun defaultTalus(): List<BlockState> = B.list("gravel", "cobblestone", "andesite", "stone")

	/** Allowed centre floor height relative to sea level. */
	internal open fun defaultBaseRange(): Span = Span.of(1.0, 400.0)

	/** Max difference of noise-only floor heights sampled over the footprint. */
	internal open fun defaultMaxRelief(): Int = 24

	internal open fun defaultCap(): Optional<Cap> = Optional.empty()
}
