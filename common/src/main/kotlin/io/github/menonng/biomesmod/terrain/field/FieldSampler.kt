package io.github.menonng.biomesmod.terrain.field

import io.github.menonng.biomesmod.compat.LevelCompat
import net.minecraft.core.Holder
import net.minecraft.core.QuartPos
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.biome.Climate
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.RandomState
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Per-dimension cached sampling for the terrain shaper: generator biomes on a coarse world-aligned grid and the
 * noise-only base height (`getBaseHeight`) on a 16-block grid. Both are pure functions of seed + coordinates (they
 * never read generated blocks), so every chunk sees the same values. The caches are lock-free direct-mapped tables
 * shared by all worldgen threads; neighbouring chunks reuse almost all of each other's samples.
 */
class FieldSampler private constructor(level: WorldGenLevel, generator: ChunkGenerator) {
	private class BiomeEntry(val key: Long, val biome: Holder<Biome>)

	val seed: Long = level.seed
	val seaLevel: Int = generator.seaLevel
	val minY: Int = LevelCompat.minY(level)
	private val height: Int = level.height

	// Sample biomes near the top of the world: there the climate "depth" parameter is far negative, so the surface
	// biome wins over cave biomes (lush caves, dripstone, deep dark) everywhere, mountains included.
	private val biomeQuartY: Int = QuartPos.fromBlock(minY + height - 8)
	private val randomState: RandomState = level.level.chunkSource.randomState()
	private val biomeSource: BiomeSource = generator.biomeSource
	private val climate: Climate.Sampler = randomState.sampler()
	private val biomes = AtomicReferenceArray<BiomeEntry>(BIOME_CACHE)
	private val heights = AtomicLongArray(HEIGHT_CACHE)

	val maxY: Int get() = minY + height - 1

	/** Surface biome at grid node (gx, gz) of the given spacing (2^shift blocks). */
	fun biomeNode(gx: Int, gz: Int, shift: Int): Holder<Biome> {
		val key = (shift.toLong() shl 56) xor ((gx.toLong() and 0xFFFFFFFL) shl 28) xor (gz.toLong() and 0xFFFFFFFL)
		val slot = (Noise.mix(key) and (BIOME_CACHE - 1).toLong()).toInt()
		val e = biomes.get(slot)
		if (e != null && e.key == key) {
			return e.biome
		}
		val x = gx shl shift
		val z = gz shl shift
		val b = biomeSource.getNoiseBiome(QuartPos.fromBlock(x), biomeQuartY, QuartPos.fromBlock(z), climate)
		biomes.set(slot, BiomeEntry(key, b))
		return b
	}

	/** Noise-only terrain height (top solid block Y, no carvers, no features) at grid node (gx, gz) of the base grid. */
	fun baseNode(generator: ChunkGenerator, heightAccessor: LevelHeightAccessor, gx: Int, gz: Int): Int {
		val key = ((gx.toLong() and 0x1FFFFFL) shl 21) or (gz.toLong() and 0x1FFFFFL)
		val slot = (Noise.mix(key * 31 + 7) and (HEIGHT_CACHE - 1).toLong()).toInt()
		val e = heights.get(slot)
		if (e != 0L && ((e ushr 12) and 0x3FFFFFFFFFFL) == key) {
			return (e and 0xFFF).toInt() - 2048
		}
		val h = generator.getBaseHeight(gx * BASE_SPACING, gz * BASE_SPACING, Heightmap.Types.OCEAN_FLOOR_WG, heightAccessor, randomState) - 1
		val packed = (1L shl 63) or (key shl 12) or ((h + 2048) and 0xFFF).toLong()
		heights.set(slot, packed)
		return h
	}

	companion object {
		const val BASE_SPACING = 16
		private const val BIOME_CACHE = 1 shl 15
		private const val HEIGHT_CACHE = 1 shl 15
		private val BY_GENERATOR: MutableMap<ChunkGenerator, FieldSampler> = Collections.synchronizedMap(WeakHashMap())

		fun of(level: WorldGenLevel, generator: ChunkGenerator): FieldSampler {
			synchronized(BY_GENERATOR) {
				var s = BY_GENERATOR[generator]
				if (s == null || s.seed != level.seed) {
					s = FieldSampler(level, generator)
					BY_GENERATOR[generator] = s
				}
				return s
			}
		}
	}
}
