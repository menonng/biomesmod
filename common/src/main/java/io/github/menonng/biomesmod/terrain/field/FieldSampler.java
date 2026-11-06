package io.github.menonng.biomesmod.terrain.field;

import io.github.menonng.biomesmod.compat.LevelCompat;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Per-dimension cached sampling for the terrain shaper: generator biomes on a coarse world-aligned grid and the
 * noise-only base height ({@code getBaseHeight}) on a 16-block grid. Both are pure functions of seed + coordinates
 * (they never read generated blocks), so every chunk sees the same values. The caches are lock-free direct-mapped
 * tables shared by all worldgen threads; neighbouring chunks reuse almost all of each other's samples.
 */
public final class FieldSampler {
	public static final int BASE_SPACING = 16;
	private static final Map<ChunkGenerator, FieldSampler> BY_GENERATOR = Collections.synchronizedMap(new WeakHashMap<>());
	private static final int BIOME_CACHE = 1 << 15;
	private static final int HEIGHT_CACHE = 1 << 15;

	private final long seed;
	private final int seaLevel;
	private final int minY;
	private final int height;
	private final int biomeQuartY;
	private final BiomeSource biomeSource;
	private final Climate.Sampler climate;
	private final RandomState randomState;
	private final AtomicReferenceArray<BiomeEntry> biomes = new AtomicReferenceArray<>(BIOME_CACHE);
	private final AtomicLongArray heights = new AtomicLongArray(HEIGHT_CACHE);

	private record BiomeEntry(long key, Holder<Biome> biome) {
	}

	private FieldSampler(WorldGenLevel level, ChunkGenerator generator) {
		this.seed = level.getSeed();
		this.seaLevel = generator.getSeaLevel();
		this.minY = LevelCompat.minY(level);
		this.height = level.getHeight();
		// Sample biomes near the top of the world: there the climate "depth" parameter is far negative, so the
		// surface biome wins over cave biomes (lush caves, dripstone, deep dark) everywhere, mountains included.
		this.biomeQuartY = QuartPos.fromBlock(minY + height - 8);
		this.randomState = level.getLevel().getChunkSource().randomState();
		this.biomeSource = generator.getBiomeSource();
		this.climate = randomState.sampler();
	}

	public static FieldSampler of(WorldGenLevel level, ChunkGenerator generator) {
		synchronized (BY_GENERATOR) {
			FieldSampler s = BY_GENERATOR.get(generator);
			if (s == null || s.seed != level.getSeed()) {
				s = new FieldSampler(level, generator);
				BY_GENERATOR.put(generator, s);
			}
			return s;
		}
	}

	public long seed() {
		return seed;
	}

	public int seaLevel() {
		return seaLevel;
	}

	public int minY() {
		return minY;
	}

	public int maxY() {
		return minY + height - 1;
	}

	/** Surface biome at grid node (gx, gz) of the given spacing (2^shift blocks). */
	public Holder<Biome> biomeNode(int gx, int gz, int shift) {
		long key = ((long) shift << 56) ^ ((gx & 0xFFFFFFFL) << 28) ^ (gz & 0xFFFFFFFL);
		int slot = (int) (Noise.mix(key) & (BIOME_CACHE - 1));
		BiomeEntry e = biomes.get(slot);
		if (e != null && e.key == key) {
			return e.biome;
		}
		int x = gx << shift;
		int z = gz << shift;
		Holder<Biome> b = biomeSource.getNoiseBiome(QuartPos.fromBlock(x), biomeQuartY, QuartPos.fromBlock(z), climate);
		biomes.set(slot, new BiomeEntry(key, b));
		return b;
	}

	/**
	 * Noise-only terrain height (top solid block Y, no carvers, no features) at grid node (gx, gz) of the 16-block
	 * base grid.
	 */
	public int baseNode(ChunkGenerator generator, LevelHeightAccessor heightAccessor, int gx, int gz) {
		long key = ((gx & 0x1FFFFFL) << 21) | (gz & 0x1FFFFFL);
		int slot = (int) (Noise.mix(key * 31 + 7) & (HEIGHT_CACHE - 1));
		long e = heights.get(slot);
		if (e != 0 && ((e >>> 12) & 0x3FFFFFFFFFFL) == key) {
			return (int) (e & 0xFFF) - 2048;
		}
		int h = generator.getBaseHeight(gx * BASE_SPACING, gz * BASE_SPACING, Heightmap.Types.OCEAN_FLOOR_WG, heightAccessor, randomState) - 1;
		long packed = (1L << 63) | (key << 12) | ((h + 2048) & 0xFFF);
		heights.set(slot, packed);
		return h;
	}
}
