package io.github.menonng.biomesmod.terrain.field.mode

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.menonng.biomesmod.terrain.field.Coverage
import io.github.menonng.biomesmod.terrain.field.Noise
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Dry crevasse fields on ice: short, roughly parallel cracks in en-echelon sets, inside noise-shaped zones, with a
 * slowly turning orientation. Never water-filled.
 *
 * @param spacing blocks between parallel cracks
 * @param width crack width (blocks)
 * @param depth maximum depth
 * @param coverage fraction of the ice covered by crevasse zones
 * @param scale size of the zones
 * @param length typical crack length
 */
data class Crevasses(val spacing: Double, val width: Double, val depth: Int, val coverage: Double, val scale: Double, val length: Double) {
	/** Crevasse depth at (x, z) in blocks (0 = none). [bias] raises the zone strength (steep ice, margins). */
	internal fun depthAt(noise: Noise, x: Double, z: Double, bias: Double): Double {
		val zone = noise.fbm(x, z, scale, 2) + bias
		val t = Coverage.threshold(coverage, 2)
		val strength = Noise.smoothstep(t, t + 0.18, zone)
		if (strength <= 0) {
			return 0.0
		}
		val theta = Math.PI * noise.fbm(x + 913, z - 271, scale * 1.6, 1)
		val c = cos(theta)
		val s = sin(theta)
		val u = x * c + z * s + 2.5 * noise.fbm(x, z, 23.0, 1)
		val v = -x * s + z * c
		val k = floor(u / spacing)
		val fu = u - (k + 0.5) * spacing
		val half = width * 0.5 * (0.7 + 0.6 * noise.cell01(k.toInt(), 5))
		if (abs(fu) > half) {
			return 0.0
		}
		val seg = noise.simplex(v / length + k * 3.7, k * 1.3)
		if (seg < 0.1) {
			return 0.0
		}
		val taper = Noise.smoothstep(0.1, 0.45, seg)
		return depth * strength * taper * (1 - abs(fu) / (half + 0.5))
	}

	companion object {
		@JvmField
		val CODEC: Codec<Crevasses> = RecordCodecBuilder.create { i ->
			i.group(
				Codec.doubleRange(3.0, 64.0).optionalFieldOf("spacing", 7.0).forGetter(Crevasses::spacing),
				Codec.doubleRange(0.5, 4.0).optionalFieldOf("width", 1.2).forGetter(Crevasses::width),
				Codec.intRange(1, 48).optionalFieldOf("depth", 10).forGetter(Crevasses::depth),
				Codec.doubleRange(0.0, 1.0).optionalFieldOf("coverage", 0.25).forGetter(Crevasses::coverage),
				Codec.doubleRange(16.0, 2048.0).optionalFieldOf("scale", 120.0).forGetter(Crevasses::scale),
				Codec.doubleRange(4.0, 256.0).optionalFieldOf("length", 16.0).forGetter(Crevasses::length),
			).apply(i) { spacing, width, depth, coverage, scale, length -> Crevasses(spacing, width, depth, coverage, scale, length) }
		}
	}
}
