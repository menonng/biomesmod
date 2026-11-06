package io.github.menonng.biomesmod.terrain.field;

import com.mojang.serialization.Codec;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

/**
 * {@code biomesmod:terrain_shaper}: column-based terrain reshaping, once per chunk (placement {@code []}, step
 * {@code raw_generation}). Writes only inside the chunk of the origin; every cross-chunk decision is a pure function
 * of the world seed and coordinates. See docs/engine-terrain.md.
 */
public final class TerrainShaperFeature extends Feature<ShaperMode> {
	private static final Map<ShaperMode, Shaper> SHAPERS = new IdentityHashMap<>();
	private static final Map<ShaperMode, Long> SHAPER_SEEDS = new IdentityHashMap<>();

	public TerrainShaperFeature(Codec<ShaperMode> codec) {
		super(codec);
	}

	@Override
	public boolean place(FeaturePlaceContext<ShaperMode> context) {
		long start = System.nanoTime();
		ShaperMode mode = context.config();
		WorldGenLevel level = context.level();
		ChunkGenerator generator = context.chunkGenerator();
		int x0 = context.origin().getX() & ~15;
		int z0 = context.origin().getZ() & ~15;
		FieldSampler sampler = FieldSampler.of(level, generator);
		Shaper shaper = shaper(mode, sampler.seed());
		ChunkFrame frame = ChunkFrame.get();
		boolean shaped = false;
		try {
			frame.begin(level, generator, sampler, mode.common(), shaper, x0, z0);
			if (frame.computeEdge(shaper.edgeReach())) {
				frame.readColumns();
				shaper.prepare(frame);
				frame.computeTargets();
				frame.resolve();
				frame.write();
				shaped = true;
			}
			FieldStats.record(mode.type(), System.nanoTime() - start, frame.writes, shaped);
		} finally {
			frame.end();
		}
		return shaped;
	}

	static Shaper shaper(ShaperMode mode, long worldSeed) {
		synchronized (SHAPERS) {
			Shaper s = SHAPERS.get(mode);
			Long seed = SHAPER_SEEDS.get(mode);
			if (s == null || seed == null || seed != worldSeed) {
				long mixed = Noise.mix(worldSeed ^ Noise.mix(mode.common().seed() * 0x9E3779B97F4A7C15L + mode.type().ordinal() * 0x632BE59BD9B4E019L + 0x1F));
				s = mode.createShaper(mixed);
				SHAPERS.put(mode, s);
				SHAPER_SEEDS.put(mode, worldSeed);
			}
			return s;
		}
	}
}
