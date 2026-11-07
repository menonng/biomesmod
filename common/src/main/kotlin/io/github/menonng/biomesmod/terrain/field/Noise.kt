package io.github.menonng.biomesmod.terrain.field

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Seeded, allocation-free 2D noise for the terrain shaper: simplex gradient noise with hashed (non-periodic)
 * gradients, fBm with a different rotation per octave (no axis-aligned tells), domain warping, ridged noise and
 * jittered cellular (Worley) noise. Every value is a pure function of seed + coordinates, so neighbouring chunks agree.
 */
class Noise(seed: Long) {
	private val s1: Int
	private val s2: Int
	private val ox: Double
	private val oz: Double

	init {
		val m = mix(seed)
		s1 = m.toInt()
		s2 = (m ushr 32).toInt()
		ox = ((mix(m + 1) ushr 11) * UNIT53) * 4096.0
		oz = ((mix(m + 2) ushr 11) * UNIT53) * 4096.0
	}

	/** A derived, independent noise. */
	fun fork(salt: Int): Noise = Noise(((s1.toLong() shl 32) xor s2.toLong()) * GOLDEN + salt * MIX1 + salt)

	private fun hash(i: Int, j: Int): Int {
		var h = s1 xor (i * 0x27D4EB2D) xor (j * 0x165667B1)
		h = h xor (h ushr 15)
		h *= 0x2C1B3C6D xor s2
		h = h xor (h ushr 12)
		h *= 0x297A2D39
		h = h xor (h ushr 15)
		return h
	}

	/** Uniform value in [0, 1) for integer cell coordinates. */
	fun cell01(i: Int, j: Int): Double = (hash(i, j) ushr 8) * UNIT24

	/** Uniform value in [0, 1) for integer cell coordinates and a salt. */
	fun cell01(i: Int, j: Int, salt: Int): Double = (hash(i + salt * 0x3C6EF372, j xor (salt * 0x0D2B79F5)) ushr 8) * UNIT24

	/** 2D simplex noise, roughly in [-1, 1]. */
	fun simplex(x0in: Double, z0in: Double): Double {
		val x = x0in + ox
		val z = z0in + oz
		val s = (x + z) * F2
		val i = floor(x + s)
		val j = floor(z + s)
		val t = (i + j) * G2
		val x0 = x - (i - t)
		val z0 = z - (j - t)
		val i1: Int
		val j1: Int
		if (x0 > z0) {
			i1 = 1
			j1 = 0
		} else {
			i1 = 0
			j1 = 1
		}
		val x1 = x0 - i1 + G2
		val z1 = z0 - j1 + G2
		val x2 = x0 - 1 + 2 * G2
		val z2 = z0 - 1 + 2 * G2
		var n = 0.0
		var t0 = 0.5 - x0 * x0 - z0 * z0
		if (t0 > 0) {
			val g = hash(i, j) and (GRADIENTS - 1)
			t0 *= t0
			n += t0 * t0 * (GX[g] * x0 + GZ[g] * z0)
		}
		var t1 = 0.5 - x1 * x1 - z1 * z1
		if (t1 > 0) {
			val g = hash(i + i1, j + j1) and (GRADIENTS - 1)
			t1 *= t1
			n += t1 * t1 * (GX[g] * x1 + GZ[g] * z1)
		}
		var t2 = 0.5 - x2 * x2 - z2 * z2
		if (t2 > 0) {
			val g = hash(i + 1, j + 1) and (GRADIENTS - 1)
			t2 *= t2
			n += t2 * t2 * (GX[g] * x2 + GZ[g] * z2)
		}
		return 99.0 * n
	}

	/** Fractal sum of [octaves] simplex layers at base frequency 1/scale, normalised to roughly [-1, 1]. */
	fun fbm(x: Double, z: Double, scale: Double, octaves: Int): Double = fbm(x, z, scale, octaves, 0.5)

	fun fbm(x: Double, z: Double, scale: Double, octaves: Int, gain: Double): Double {
		var fx = x / scale
		var fz = z / scale
		var sum = 0.0
		var amp = 1.0
		var norm = 0.0
		for (o in 0 until octaves) {
			val rx = fx * ROT_C[o] - fz * ROT_S[o]
			val rz = fx * ROT_S[o] + fz * ROT_C[o]
			sum += amp * simplex(rx + o * 31.7, rz - o * 17.3)
			norm += amp
			amp *= gain
			fx *= 2.03
			fz *= 2.03
		}
		return sum / norm * (if (octaves > 1) 1.35 else 1.0)
	}

	/** fBm mapped to [0, 1]. */
	fun fbm01(x: Double, z: Double, scale: Double, octaves: Int): Double = clamp(0.5 + 0.5 * fbm(x, z, scale, octaves), 0.0, 1.0)

	/** Ridged noise: 1 at the zero-contours of an fBm field, falling off on both sides. */
	fun ridged(x: Double, z: Double, scale: Double, octaves: Int): Double = 1.0 - min(1.0, abs(fbm(x, z, scale, octaves)))

	/**
	 * Jittered cellular noise. Writes {F1, F2, cellX, cellZ, pointX, pointZ} of the nearest feature point into out
	 * (distances in cell units).
	 */
	fun worley(x: Double, z: Double, jitter: Double, out: DoubleArray) {
		val ci = floor(x)
		val cj = floor(z)
		var f1 = Double.MAX_VALUE
		var f2 = Double.MAX_VALUE
		var bi = 0
		var bj = 0
		var bx = 0.0
		var bz = 0.0
		for (dj in -1..1) {
			for (di in -1..1) {
				val i = ci + di
				val j = cj + dj
				val px = i + 0.5 + (cell01(i, j, 1) - 0.5) * jitter
				val pz = j + 0.5 + (cell01(i, j, 2) - 0.5) * jitter
				val dx = px - x
				val dz = pz - z
				val dd = dx * dx + dz * dz
				if (dd < f1) {
					f2 = f1
					f1 = dd
					bi = i
					bj = j
					bx = px
					bz = pz
				} else if (dd < f2) {
					f2 = dd
				}
			}
		}
		out[0] = sqrt(f1)
		out[1] = sqrt(f2)
		out[2] = bi.toDouble()
		out[3] = bj.toDouble()
		out[4] = bx
		out[5] = bz
	}

	/**
	 * Distance (in cell units) from p to the nearest Voronoi edge, the true perpendicular distance to the bisector of
	 * the two nearest points (unlike F2-F1, it gives constant-width lines).
	 */
	fun voronoiEdge(x: Double, z: Double, jitter: Double): Double {
		val ci = floor(x)
		val cj = floor(z)
		var bestD = Double.MAX_VALUE
		var ax = 0.0
		var az = 0.0
		for (dj in -1..1) {
			for (di in -1..1) {
				val i = ci + di
				val j = cj + dj
				val px = i + 0.5 + (cell01(i, j, 1) - 0.5) * jitter
				val pz = j + 0.5 + (cell01(i, j, 2) - 0.5) * jitter
				val dd = (px - x) * (px - x) + (pz - z) * (pz - z)
				if (dd < bestD) {
					bestD = dd
					ax = px
					az = pz
				}
			}
		}
		var edge = Double.MAX_VALUE
		for (dj in -2..2) {
			for (di in -2..2) {
				val i = ci + di
				val j = cj + dj
				val px = i + 0.5 + (cell01(i, j, 1) - 0.5) * jitter
				val pz = j + 0.5 + (cell01(i, j, 2) - 0.5) * jitter
				val ex = px - ax
				val ez = pz - az
				val len2 = ex * ex + ez * ez
				if (len2 < 1e-9) {
					continue
				}
				val mx = (px + ax) * 0.5 - x
				val mz = (pz + az) * 0.5 - z
				val dist = (mx * ex + mz * ez) / sqrt(len2)
				if (dist < edge) {
					edge = dist
				}
			}
		}
		return edge
	}

	companion object {
		private val F2 = 0.5 * (sqrt(3.0) - 1.0)
		private val G2 = (3.0 - sqrt(3.0)) / 6.0
		private const val GRADIENTS = 32
		private const val MAX_OCTAVES = 8
		private const val UNIT53 = 1.0 / 9007199254740992.0 // 2^-53
		private const val UNIT24 = 1.0 / 16777216.0 // 2^-24
		private val GOLDEN = 0x9E3779B97F4A7C15uL.toLong()
		private val MIX1 = 0xBF58476D1CE4E5B9uL.toLong()
		private val MIX2 = 0x94D049BB133111EBuL.toLong()
		private val GX = DoubleArray(GRADIENTS) { cos(2 * Math.PI * (it + 0.37) / GRADIENTS) }
		private val GZ = DoubleArray(GRADIENTS) { sin(2 * Math.PI * (it + 0.37) / GRADIENTS) }

		// Irrational-ish steps, so octave lattices never line up.
		private val ROT_C = DoubleArray(MAX_OCTAVES) { cos(it * 1.1781 + 0.3) }
		private val ROT_S = DoubleArray(MAX_OCTAVES) { sin(it * 1.1781 + 0.3) }

		fun mix(z0: Long): Long {
			var z = z0
			z = (z xor (z ushr 30)) * MIX1
			z = (z xor (z ushr 27)) * MIX2
			return z xor (z ushr 31)
		}

		fun floor(v: Double): Int {
			val i = v.toInt()
			return if (v < i) i - 1 else i
		}

		fun clamp(v: Double, lo: Double, hi: Double): Double = if (v < lo) lo else if (v > hi) hi else v

		fun smoothstep(e0: Double, e1: Double, x: Double): Double {
			val t = clamp((x - e0) / (e1 - e0), 0.0, 1.0)
			return t * t * (3 - 2 * t)
		}

		fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t

		/** Polynomial smooth maximum with blend width k. */
		fun smax(a: Double, b: Double, k: Double): Double {
			if (k <= 0) {
				return max(a, b)
			}
			val h = max(k - abs(a - b), 0.0) / k
			return max(a, b) + h * h * k * 0.25
		}

		/** Polynomial smooth minimum with blend width k. */
		fun smin(a: Double, b: Double, k: Double): Double = -smax(-a, -b, k)
	}
}
