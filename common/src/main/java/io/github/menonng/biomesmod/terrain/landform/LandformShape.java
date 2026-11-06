package io.github.menonng.biomesmod.terrain.landform;

import io.github.menonng.biomesmod.terrain.util.StrataPalette;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A landform shape: its JSON parameters plus a factory for instances. One subclass per {@code "shape"} value.
 * Instances must be a pure function of the {@link Site} (world seed, cell, noise-only terrain).
 */
public abstract class LandformShape {
	/** The {@code "shape"} value. */
	public abstract String name();

	/**
	 * Upper bound, in blocks from the instance centre, of anything an instance of this config may write.
	 * Used to find instances that overlap a chunk.
	 */
	abstract int maxReach();

	/** Creates the instance at the site's centre, or returns null if the terrain does not suit the shape. */
	abstract Landform create(Site site);

	abstract StrataPalette defaultPalette();

	List<BlockState> defaultTalus() {
		return B.list("gravel", "cobblestone", "andesite", "stone");
	}

	/** Allowed centre floor height relative to sea level. */
	Span defaultBaseRange() {
		return Span.of(1, 400);
	}

	/** Max difference of noise-only floor heights sampled over the footprint. */
	int defaultMaxRelief() {
		return 24;
	}

	Optional<Cap> defaultCap() {
		return Optional.empty();
	}
}
