package io.github.menonng.biomesmod.terrain.field.mode

import io.github.menonng.biomesmod.terrain.field.Noise
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A family of roughly parallel, meandering centre lines (canyons, glacial valleys, fjords). The lines are the level
 * sets `across + shift(along, across) = k * spacing`. The shift's derivative across the lines stays well below 1, so
 * each line is a graph over the along axis: lines never fold back into loops, rings or crescents.
 */
internal class Meander(private val noise: Noise, directionDeg: Double, spacing: Double, amp: Double, wavelength: Double) {
	private val cosD: Double
	private val sinD: Double
	private val spacing: Double = max(32.0, spacing)

	// keep |d shift / d across| <= ~0.35 whatever the user sets
	private val amp: Double = min(max(0.0, amp), this.spacing * 0.2)
	private val wavelength: Double = max(32.0, wavelength)
	private val offset: Double = noise.cell01(17, 29) * this.spacing

	init {
		val a = Math.toRadians(directionDeg)
		cosD = cos(a)
		sinD = sin(a)
	}

	/** out[0] signed perpendicular distance to the nearest line, out[1] along coordinate, out[2] line index. */
	fun eval(x: Double, z: Double, out: DoubleArray) {
		val along = x * cosD + z * sinD
		val across = -x * sinD + z * cosD + offset
		val c = coord(along, across)
		val k = floor(c / spacing + 0.5)
		val dc = c - k * spacing
		val h = 1.5
		val ga = (coord(along + h, across) - coord(along - h, across)) / (2 * h)
		val gc = (coord(along, across + h) - coord(along, across - h)) / (2 * h)
		val grad = max(0.3, sqrt(ga * ga + gc * gc))
		out[0] = dc / grad
		out[1] = along
		out[2] = k
	}

	private fun coord(along: Double, across: Double): Double {
		val s = across / (spacing * 0.8)
		return across + amp * (0.72 * noise.simplex(along / wavelength, s) + 0.28 * noise.simplex(along / (wavelength * 0.41), s + 7.1))
	}
}
