package io.github.menonng.biomesmod.terrain.landform

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Stateless, allocation-free hash noise for landforms. Every value is a pure function of (seed, coordinates), so
 * neighbouring chunks always agree.
 */
internal object LNoise {
	private const val TAU = Math.PI * 2
	private val G2X = DoubleArray(16) { cos(TAU * (it + 0.5) / 16) }
	private val G2Y = DoubleArray(16) { sin(TAU * (it + 0.5) / 16) }
	private val M1 = 0xff51afd7ed558ccduL.toLong()
	private val M2 = 0xc4ceb9fe1a85ec53uL.toLong()
	private val GOLDEN = 0x9E3779B97F4A7C15uL.toLong()
	private val MURMUR = 0xC2B2AE3D27D4EB4FuL.toLong()
	private const val UNIT53 = 1.0 / 9007199254740992.0 // 2^-53

	fun mix(z0: Long): Long {
		var z = z0
		z = (z xor (z ushr 33)) * M1
		z = (z xor (z ushr 33)) * M2
		return z xor (z ushr 33)
	}

	fun hash(seed: Long, x: Int, z: Int): Long = mix(seed xor (x * GOLDEN) xor (z * MURMUR))

	fun hash(seed: Long, x: Int, y: Int, z: Int): Long = mix(seed xor (x * GOLDEN) xor (y * 0x165667B19E3779F9L) xor (z * MURMUR))

	/** Derives an independent seed. */
	fun sub(seed: Long, salt: Long): Long = mix(seed + salt * 0x632BE59BD9B4E019L)

	/** Uniform [0, 1) from a hash. */
	fun unit(h: Long): Double = (h ushr 11) * UNIT53

	fun unit(seed: Long, x: Int, z: Int): Double = unit(hash(seed, x, z))

	fun unit(seed: Long, x: Int, y: Int, z: Int): Double = unit(hash(seed, x, y, z))

	fun floor(v: Double): Int {
		val i = v.toInt()
		return if (v < i) i - 1 else i
	}

	private fun fade(t: Double): Double = t * t * t * (t * (t * 6 - 15) + 10)

	private fun grad2(seed: Long, ix: Int, iy: Int, dx: Double, dy: Double): Double {
		val h = (hash(seed, ix, iy) ushr 60).toInt()
		return G2X[h] * dx + G2Y[h] * dy
	}

	/** 2D gradient noise, roughly in [-1, 1]. */
	fun noise2(seed: Long, x: Double, y: Double): Double {
		val ix = floor(x)
		val iy = floor(y)
		val fx = x - ix
		val fy = y - iy
		val u = fade(fx)
		val v = fade(fy)
		val n00 = grad2(seed, ix, iy, fx, fy)
		val n10 = grad2(seed, ix + 1, iy, fx - 1, fy)
		val n01 = grad2(seed, ix, iy + 1, fx, fy - 1)
		val n11 = grad2(seed, ix + 1, iy + 1, fx - 1, fy - 1)
		val a = n00 + u * (n10 - n00)
		val b = n01 + u * (n11 - n01)
		return (a + v * (b - a)) * 1.414
	}

	/** 1D gradient noise, roughly in [-1, 1]. */
	fun noise1(seed: Long, x: Double): Double {
		val ix = floor(x)
		val fx = x - ix
		val g0 = unit(hash(seed, ix, 0)) * 2 - 1
		val g1 = unit(hash(seed, ix + 1, 0)) * 2 - 1
		val n0 = g0 * fx
		val n1 = g1 * (fx - 1)
		return (n0 + fade(fx) * (n1 - n0)) * 2.0
	}

	/** Fractal sum of 2D noise, normalised to roughly [-1, 1]. */
	fun fbm2(seed: Long, x0: Double, y0: Double, octaves: Int): Double {
		var x = x0
		var y = y0
		var sum = 0.0
		var amp = 1.0
		var norm = 0.0
		for (o in 0 until octaves) {
			sum += amp * noise2(seed + o * 0x51ED27L, x, y)
			norm += amp
			amp *= 0.5
			x *= 2.03
			y *= 2.03
		}
		return sum / norm
	}

	/**
	 * Periodic noise around a circle: [features] is roughly the number of bumps over 360 degrees. Sampling a 2D noise
	 * on a circle avoids a seam at theta = +-pi.
	 */
	fun angular(seed: Long, theta: Double, features: Double): Double {
		val r = features / TAU
		return noise2(seed, cos(theta) * r + 31.7, sin(theta) * r - 17.3)
	}

	/** Two-octave angular noise. */
	fun angular2(seed: Long, theta: Double, features: Double): Double =
		0.7 * angular(seed, theta, features) + 0.3 * angular(seed xor 0x5DEECE66DL, theta, features * 2.7)

	/** Smallest signed angle difference a - b in (-pi, pi]. */
	fun angleDiff(a: Double, b: Double): Double {
		var d = (a - b) % TAU
		if (d > Math.PI) {
			d -= TAU
		} else if (d <= -Math.PI) {
			d += TAU
		}
		return d
	}

	fun smoothstep(e0: Double, e1: Double, x: Double): Double {
		val t = max(0.0, min(1.0, (x - e0) / (e1 - e0)))
		return t * t * (3 - 2 * t)
	}

	fun clamp(v: Double, lo: Double, hi: Double): Double = if (v < lo) lo else if (v > hi) hi else v

	fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

	/** Small deterministic seeded RNG for per-instance decisions (never the context random). */
	class Rng(private var state: Long) {
		fun nextLong(): Long {
			state += GOLDEN
			return mix(state)
		}

		fun next(): Double = (nextLong() ushr 11) * UNIT53

		fun range(lo: Double, hi: Double): Double = lo + (hi - lo) * next()

		fun nextInt(bound: Int): Int = floor(next() * bound).toInt()

		fun chance(p: Double): Boolean = next() < p

		fun range(s: Span): Double = s.min + (s.max - s.min) * next()

		fun rangeInt(s: Span): Int = floor(s.min + (s.max - s.min + 0.999) * next()).toInt()

		fun angle(): Double = next() * TAU - Math.PI
	}
}
