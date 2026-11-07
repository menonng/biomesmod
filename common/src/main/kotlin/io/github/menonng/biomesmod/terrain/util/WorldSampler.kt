package io.github.menonng.biomesmod.terrain.util

import net.minecraft.core.Holder
import net.minecraft.core.QuartPos
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.biome.Climate
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.RandomState
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Shared, read-only queries that work anywhere in the world, including chunks that are not generated yet. Engine
 * features use this to make decisions that must agree across chunk borders (a feature may only WRITE inside its own
 * chunk, but it can SAMPLE anywhere through this class).
 */
class WorldSampler private constructor(val level: WorldGenLevel, private val generator: ChunkGenerator) {
	private val randomState: RandomState = level.level.chunkSource.randomState()
	private val biomeSource: BiomeSource = generator.biomeSource
	private val climate: Climate.Sampler = randomState.sampler()

	val seed: Long get() = level.seed

	val seaLevel: Int get() = generator.seaLevel

	/** Biome the generator assigns at a block position (same answer before and after the chunk exists). */
	fun biomeAt(x: Int, y: Int, z: Int): Holder<Biome> =
		biomeSource.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z), climate)

	fun isBiome(x: Int, y: Int, z: Int, key: ResourceKey<Biome>): Boolean = biomeAt(x, y, z).`is`(key)

	/** Terrain height from the noise generator alone (no features), top of the first solid column (OCEAN_FLOOR_WG). */
	fun baseFloorHeight(x: Int, z: Int): Int = generator.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, randomState)

	/** Terrain height including fluids (WORLD_SURFACE_WG): water surface over oceans. */
	fun baseSurfaceHeight(x: Int, z: Int): Int = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, randomState)

	/**
	 * Approximate distance in blocks from (x, z) to the nearest position whose biome (sampled at height y) is not
	 * [key]. Samples rings of radius step, 2*step, ... up to max; returns max if none found.
	 */
	fun distanceToEdge(x: Int, y: Int, z: Int, key: ResourceKey<Biome>, max: Int, step: Int): Int {
		if (!isBiome(x, y, z, key)) {
			return 0
		}
		var r = step
		while (r <= max) {
			val samples = max(8, ceil(2 * Math.PI * r / step).toInt())
			for (i in 0 until samples) {
				val a = 2 * Math.PI * i / samples
				val sx = x + (cos(a) * r).roundToInt()
				val sz = z + (sin(a) * r).roundToInt()
				if (!isBiome(sx, y, sz, key)) {
					return r
				}
			}
			r += step
		}
		return max
	}

	companion object {
		fun of(context: FeaturePlaceContext<*>): WorldSampler = WorldSampler(context.level(), context.chunkGenerator())

		fun of(level: WorldGenLevel, generator: ChunkGenerator): WorldSampler = WorldSampler(level, generator)
	}
}
