package io.github.menonng.biomesmod.terrain.util;

import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

/**
 * Shared, read-only queries that work anywhere in the world, including chunks that are not generated yet.
 * Engine features use this to make decisions that must agree across chunk borders
 * (a feature may only WRITE inside its own chunk, but it can SAMPLE anywhere through this class).
 *
 * <p>Owner: coordinator. Engine agents may use it; put extra helpers in your own package instead of editing it.
 */
public final class WorldSampler {
	private final WorldGenLevel level;
	private final ChunkGenerator generator;
	private final RandomState randomState;
	private final BiomeSource biomeSource;
	private final Climate.Sampler climate;

	private WorldSampler(WorldGenLevel level, ChunkGenerator generator) {
		this.level = level;
		this.generator = generator;
		ServerLevel server = level.getLevel();
		this.randomState = server.getChunkSource().randomState();
		this.biomeSource = generator.getBiomeSource();
		this.climate = randomState.sampler();
	}

	public static WorldSampler of(FeaturePlaceContext<?> context) {
		return new WorldSampler(context.level(), context.chunkGenerator());
	}

	public static WorldSampler of(WorldGenLevel level, ChunkGenerator generator) {
		return new WorldSampler(level, generator);
	}

	public WorldGenLevel level() {
		return level;
	}

	public long seed() {
		return level.getSeed();
	}

	public int seaLevel() {
		return generator.getSeaLevel();
	}

	/** Biome the generator assigns at a block position (same answer before and after the chunk exists). */
	public Holder<Biome> biomeAt(int x, int y, int z) {
		return biomeSource.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), climate);
	}

	public boolean isBiome(int x, int y, int z, ResourceKey<Biome> key) {
		return biomeAt(x, y, z).is(key);
	}

	/** Terrain height from the noise generator alone (no features), top of the first non-air, non-liquid... column (OCEAN_FLOOR_WG). */
	public int baseFloorHeight(int x, int z) {
		return generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState);
	}

	/** Terrain height including fluids (WORLD_SURFACE_WG): water surface over oceans. */
	public int baseSurfaceHeight(int x, int z) {
		return generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState);
	}

	/**
	 * Approximate distance in blocks from (x, z) to the nearest position whose biome (sampled at height y)
	 * is not {@code key}. Samples rings of radius step, 2*step, ... up to max; returns max if none found.
	 */
	public int distanceToEdge(int x, int y, int z, ResourceKey<Biome> key, int max, int step) {
		if (!isBiome(x, y, z, key)) {
			return 0;
		}
		for (int r = step; r <= max; r += step) {
			int samples = Math.max(8, (int) Math.ceil(2 * Math.PI * r / step));
			for (int i = 0; i < samples; i++) {
				double a = 2 * Math.PI * i / samples;
				int sx = x + (int) Math.round(Math.cos(a) * r);
				int sz = z + (int) Math.round(Math.sin(a) * r);
				if (!isBiome(sx, y, sz, key)) {
					return r;
				}
			}
		}
		return max;
	}
}
