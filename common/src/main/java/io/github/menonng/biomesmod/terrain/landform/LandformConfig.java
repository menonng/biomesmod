package io.github.menonng.biomesmod.terrain.landform;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.menonng.biomesmod.terrain.util.StrataPalette;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

/**
 * Config of {@code biomesmod:landform}: grid placement + biome gating + materials, plus the shape's own
 * parameters at the same JSON level (dispatched on {@code "shape"}). See docs/engine-landform.md.
 */
public final class LandformConfig implements FeatureConfiguration {
	private static final Codec<String> RISE = Codec.STRING.validate(s -> s.equals("ground") || s.equals("sea_level")
			? DataResult.success(s) : DataResult.error(() -> "rise_from must be ground or sea_level: " + s));

	public static final Codec<LandformConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
			LandformShapes.MAP_CODEC.forGetter(LandformConfig::shape),
			Codec.intRange(8, 4096).optionalFieldOf("spacing", 128).forGetter(c -> c.spacing),
			Codec.floatRange(0, 1).optionalFieldOf("chance", 0.5f).forGetter(c -> c.chance),
			Codec.floatRange(0, 1).optionalFieldOf("jitter", 0.75f).forGetter(c -> c.jitter),
			Codec.INT.optionalFieldOf("salt", 0).forGetter(c -> c.salt),
			BiomeFilter.CODEC.optionalFieldOf("biomes", BiomeFilter.ANY).forGetter(c -> c.biomes),
			Codec.BOOL.optionalFieldOf("require_inside", true).forGetter(c -> c.requireInside),
			StrataPalette.CODEC.optionalFieldOf("palette").forGetter(c -> c.palette),
			BlockState.CODEC.listOf().optionalFieldOf("talus_blocks", List.of()).forGetter(c -> c.talusBlocks),
			BlockState.CODEC.listOf().optionalFieldOf("surface_blocks", List.of()).forGetter(c -> c.surfaceBlocks),
			Cap.CODEC.optionalFieldOf("cap").forGetter(c -> c.cap),
			Span.CODEC.optionalFieldOf("base_y_range").forGetter(c -> c.baseYRange),
			Codec.intRange(0, 512).optionalFieldOf("max_relief").forGetter(c -> c.maxRelief),
			RISE.optionalFieldOf("rise_from", "ground").forGetter(c -> c.riseFrom)
	).apply(i, LandformConfig::new));

	private final LandformShape shape;
	final int spacing;
	final float chance;
	final float jitter;
	final int salt;
	final BiomeFilter biomes;
	final boolean requireInside;
	private final Optional<StrataPalette> palette;
	private final List<BlockState> talusBlocks;
	final List<BlockState> surfaceBlocks;
	private final Optional<Cap> cap;
	private final Optional<Span> baseYRange;
	private final Optional<Integer> maxRelief;
	private final String riseFrom;

	/** Resolved instances per (generator, seed, cell). Never shared between configs. */
	final ConcurrentHashMap<CellKey, Object> cache = new ConcurrentHashMap<>();

	record CellKey(Object generator, long seed, int cx, int cz) {
		@Override
		public boolean equals(Object o) {
			return o instanceof CellKey k && k.generator == generator && k.seed == seed && k.cx == cx && k.cz == cz;
		}

		@Override
		public int hashCode() {
			return System.identityHashCode(generator) * 31 + Long.hashCode(seed) * 17 + cx * 92821 + cz;
		}
	}

	public LandformConfig(LandformShape shape, int spacing, float chance, float jitter, int salt, BiomeFilter biomes,
			boolean requireInside, Optional<StrataPalette> palette, List<BlockState> talusBlocks, List<BlockState> surfaceBlocks,
			Optional<Cap> cap, Optional<Span> baseYRange, Optional<Integer> maxRelief, String riseFrom) {
		this.shape = shape;
		this.spacing = spacing;
		this.chance = chance;
		this.jitter = jitter;
		this.salt = salt;
		this.biomes = biomes;
		this.requireInside = requireInside;
		this.palette = palette;
		this.talusBlocks = talusBlocks;
		this.surfaceBlocks = surfaceBlocks;
		this.cap = cap;
		this.baseYRange = baseYRange;
		this.maxRelief = maxRelief;
		this.riseFrom = riseFrom;
	}

	public LandformShape shape() {
		return shape;
	}

	StrataPalette palette() {
		return palette.orElseGet(shape::defaultPalette);
	}

	List<BlockState> talus() {
		return talusBlocks.isEmpty() ? shape.defaultTalus() : talusBlocks;
	}

	Optional<Cap> cap() {
		return cap.isPresent() ? cap : shape.defaultCap();
	}

	Span baseYRange() {
		return baseYRange.orElseGet(shape::defaultBaseRange);
	}

	int maxRelief() {
		return maxRelief.orElseGet(shape::defaultMaxRelief);
	}

	boolean riseFromSea() {
		return riseFrom.equals("sea_level");
	}

	/** Builds a config in code (showcase/tests) from a shape with all other settings at their defaults. */
	public static LandformConfig of(LandformShape shape, int spacing, float chance, int salt) {
		return new LandformConfig(shape, spacing, chance, 0.75f, salt, BiomeFilter.ANY, true, Optional.empty(), List.of(),
				List.of(), Optional.empty(), Optional.empty(), Optional.empty(), "ground");
	}
}
