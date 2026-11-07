package io.github.menonng.biomesmod.terrain.field

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Maps an area fraction ("coverage") to a threshold on [Noise.fbm] so that roughly that fraction of the plane lies
 * above it. The fBm spread is measured once per octave count.
 */
object Coverage {
	private val STD = DoubleArray(9).also { std ->
		val n = Noise(0x5EEDL)
		for (oct in 1 until std.size) {
			var sum = 0.0
			var sum2 = 0.0
			var count = 0
			for (j in 0 until 96) {
				for (i in 0 until 96) {
					val v = n.fbm(i * 13.7, j * 13.7, 9.0, oct)
					sum += v
					sum2 += v * v
					count++
				}
			}
			val mean = sum / count
			std[oct] = sqrt(max(1e-6, sum2 / count - mean * mean))
		}
	}

	/** Threshold t such that P(fbm(octaves) > t) is about coverage. */
	fun threshold(coverage: Double, octaves: Int): Double {
		val c = Noise.clamp(coverage, 1e-4, 1 - 1e-4)
		return STD[max(1, min(8, octaves))] * normalQuantile(1 - c)
	}

	/** Standard deviation of fbm with the given octave count. */
	fun std(octaves: Int): Double = STD[max(1, min(8, octaves))]

	/** Inverse standard normal CDF (Abramowitz and Stegun 26.2.23, error below 5e-4). */
	fun normalQuantile(p: Double): Double {
		if (p <= 0) {
			return -8.0
		}
		if (p >= 1) {
			return 8.0
		}
		val lower = p < 0.5
		val q = if (lower) p else 1 - p
		val t = sqrt(-2 * ln(q))
		val z = t - (2.515517 + 0.802853 * t + 0.010328 * t * t) / (1 + 1.432788 * t + 0.189269 * t * t + 0.001308 * t * t * t)
		return if (lower) -z else z
	}
}
