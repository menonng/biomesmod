package io.github.menonng.biomesmod.terrain.field.mode

import io.github.menonng.biomesmod.terrain.field.Noise
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A branching drainage network (canyons, glacial valleys): tributaries join a trunk at angles, the way water and ice
 * actually drain land, instead of running side by side.
 *
 * The plane is cut into cells of [spacing]; each holds one jittered node. A node drains to whichever of its eight
 * neighbours lies lowest on a smooth pseudo-elevation (a gentle fall towards [directionDeg] plus broad noise), so the
 * links form trees. The `order` of a node counts the nodes draining through it: headwaters are 1, trunks many. The
 * position is warped before the lookup, so the reaches curve.
 */
internal class Drainage(private val noise: Noise, directionDeg: Double, spacing: Double) {
	private val spacing: Double = max(48.0, spacing)
	private val cosD: Double
	private val sinD: Double
	private val links = ConcurrentHashMap<Long, Long>()
	private val orders = ConcurrentHashMap<Long, Int>()

	init {
		val a = Math.toRadians(directionDeg)
		cosD = cos(a)
		sinD = sin(a)
	}

	private fun key(i: Int, j: Int): Long = (i.toLong() shl 32) xor (j.toLong() and 0xFFFFFFFFL)

	private fun nodeX(i: Int, j: Int): Double = (i + 0.2 + 0.6 * noise.cell01(i, j, 1)) * spacing

	private fun nodeZ(i: Int, j: Int): Double = (j + 0.2 + 0.6 * noise.cell01(i, j, 2)) * spacing

	/** Pseudo-elevation of a node: falls gently along the drainage direction, with broad swells that bend the trunks. */
	private fun elev(i: Int, j: Int): Double {
		val x = nodeX(i, j)
		val z = nodeZ(i, j)
		return -(x * cosD + z * sinD) / (spacing * 1.5) + 0.75 * noise.simplex(x / (spacing * 4.2), z / (spacing * 4.2)) +
			0.28 * noise.simplex(x / (spacing * 1.7) + 31.0, z / (spacing * 1.7) - 17.0)
	}

	/** The cell this one drains to, as a key; itself if it is a sink. */
	private fun link(i: Int, j: Int): Long = links.computeIfAbsent(key(i, j)) {
		var best = elev(i, j)
		var bi = i
		var bj = j
		for (dj in -1..1) {
			for (di in -1..1) {
				if (di == 0 && dj == 0) continue
				// Diagonal links are a little longer; weigh them so the pattern is not a lattice.
				val e = elev(i + di, j + dj) + (if (di != 0 && dj != 0) 0.12 else 0.0)
				if (e < best) {
					best = e
					bi = i + di
					bj = j + dj
				}
			}
		}
		key(bi, bj)
	}

	/** How many nodes drain through this one (itself included), followed [depth] links upstream. */
	private fun order(i: Int, j: Int, depth: Int): Int {
		if (depth == 0) return 1
		val k = key(i, j)
		if (depth == MAX_DEPTH) orders[k]?.let { return it }
		var n = 1
		for (dj in -1..1) {
			for (di in -1..1) {
				if ((di != 0 || dj != 0) && link(i + di, j + dj) == k) {
					n += order(i + di, j + dj, depth - 1)
				}
			}
		}
		if (depth == MAX_DEPTH) orders[k] = n
		return n
	}

	/**
	 * out[0] distance to the nearest channel centre line, out[1] a coordinate that runs along that reach, out[2] the
	 * size of the channel there in [0, 1] (0 = a headwater gully, 1 = a trunk).
	 */
	fun eval(x0: Double, z0: Double, out: DoubleArray) {
		val w = spacing * 0.22
		val x = x0 + w * noise.simplex(x0 / (spacing * 0.9), z0 / (spacing * 0.9) + 40.0)
		val z = z0 + w * noise.simplex(x0 / (spacing * 0.9) - 40.0, z0 / (spacing * 0.9))
		val ci = floor(x / spacing).toInt()
		val cj = floor(z / spacing).toInt()
		var bestD = Double.MAX_VALUE
		var bestAlong = 0.0
		var bestSize = 0.0
		for (j in cj - 2..cj + 2) {
			for (i in ci - 2..ci + 2) {
				val to = link(i, j)
				val ti = (to shr 32).toInt()
				val tj = to.toInt()
				if (ti == i && tj == j) continue
				val ax = nodeX(i, j)
				val az = nodeZ(i, j)
				val bx = nodeX(ti, tj)
				val bz = nodeZ(ti, tj)
				val ex = bx - ax
				val ez = bz - az
				val len2 = ex * ex + ez * ez
				val t = (((x - ax) * ex + (z - az) * ez) / len2).coerceIn(0.0, 1.0)
				val dx = x - (ax + ex * t)
				val dz = z - (az + ez * t)
				val a = order(i, j, MAX_DEPTH)
				val b = order(ti, tj, MAX_DEPTH)
				// The channel grows along the reach, from the size of its upper node to that of the lower one.
				val size = min(1.0, ((a + (b - a) * t) - 1.0) / 11.0)
				// A wider channel reaches further: compare distances relative to the channel size.
				val d = sqrt(dx * dx + dz * dz)
				val score = d / (0.35 + 0.65 * size)
				if (score < bestD) {
					bestD = score
					out[0] = d
					bestAlong = (i * 131 + j * 197) * spacing + t * sqrt(len2)
					bestSize = size
				}
			}
		}
		if (bestD == Double.MAX_VALUE) {
			out[0] = spacing * 4
		}
		out[1] = bestAlong
		out[2] = bestSize
	}

	companion object {
		private const val MAX_DEPTH = 7
	}
}
