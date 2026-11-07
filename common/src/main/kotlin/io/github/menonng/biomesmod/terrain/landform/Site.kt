package io.github.menonng.biomesmod.terrain.landform

import io.github.menonng.biomesmod.compat.LandformCompat
import io.github.menonng.biomesmod.compat.LevelCompat
import io.github.menonng.biomesmod.terrain.util.WorldSampler
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Everything a shape may use to build one instance: the centre, a seeded RNG, and noise-only terrain samples. Only
 * valid while the instance is being created; instances must not keep it (it references a chunk region).
 */
internal class Site(
	@JvmField val sampler: WorldSampler,
	@JvmField val config: LandformConfig,
	@JvmField val seed: Long,
	@JvmField val x: Int,
	@JvmField val z: Int,
	@JvmField val floor0: Int,
) {
	@JvmField
	val sea: Int = sampler.seaLevel

	@JvmField
	val minY: Int = LevelCompat.minY(sampler.level)

	@JvmField
	val maxY: Int = LandformCompat.maxY(sampler.level)

	@JvmField
	val rng = LNoise.Rng(LNoise.sub(seed, 0x51732))

	/** Noise-only top solid block Y at a position (no features, no carvers). */
	fun floor(px: Double, pz: Double): Int = sampler.baseFloorHeight(LNoise.floor(px), LNoise.floor(pz)) - 1

	/** Floors at [n] points on a circle around the centre. */
	fun ring(radius: Double, n: Int): IntArray = IntArray(n) {
		val a = Math.PI * 2 * it / n
		floor(x + cos(a) * radius, z + sin(a) * radius)
	}

	/** True if the footprint samples (plus the centre) vary by no more than max_relief. */
	fun reliefOk(samples: IntArray): Boolean = max(samples, floor0) - min(samples, floor0) <= config.maxRelief()

	/** Base used for heights: the floor, or the sea surface when rise_from = sea_level and the floor is lower. */
	fun riseBase(base: Int): Int = if (config.riseFromSea()) max(base, sea) else base

	/** Highest Y a landform top may reach (keeps a margin below the build limit). */
	fun ceiling(): Int = maxY - 6

	fun sub(salt: Long): Long = LNoise.sub(seed, salt)

	companion object {
		fun mean(v: IntArray, extra: Int): Int = ((extra.toLong() + v.sum()) / (v.size + 1).toDouble()).roundToLong().toInt()

		fun min(v: IntArray, extra: Int): Int = min(v.minOrNull() ?: extra, extra)

		fun max(v: IntArray, extra: Int): Int = max(v.maxOrNull() ?: extra, extra)
	}
}
