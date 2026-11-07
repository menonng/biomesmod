package io.github.menonng.biomesmod.terrain.landform

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.menonng.biomesmod.terrain.util.StrataPalette
import net.minecraft.world.level.block.state.BlockState
import java.util.Optional
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * `butte`: flat-topped, steep-walled, layered buttes and mesas on a sloping pedestal and talus apron, optionally with a
 * detached "mitten" thumb (Monument Valley); with `dome` and `clefts`, rounded sandstone inselbergs split by slot
 * canyons (Wadi Rum).
 */
class ButteShape internal constructor(
	internal val radius: Span,
	internal val elongation: Span,
	internal val height: Span,
	internal val cliff: Float,
	internal val pedestal: Float,
	internal val dome: Float,
	internal val thumbs: Float,
	internal val clefts: Float,
	internal val roughness: Float,
	internal val pedestalPalette: Optional<StrataPalette>,
) : LandformShape() {
	override fun name(): String = NAME

	private fun pedRun(h: Double): Double = pedestal * h * (1 - cliff) * 1.6 + 3

	override fun maxReach(): Int {
		val r = radius.max * sqrt(elongation.max.toDouble()) * (1 + roughness) * 1.35 + radius.max * roughness * 0.7
		return ceil(r + pedRun(height.max.toDouble()) + 4 + radius.max * 0.15 + 2).toInt()
	}

	override fun defaultPalette(): StrataPalette = B.strata(null, 0, "orange_terracotta", 4, "red_terracotta", 3, "terracotta", 1,
		"orange_terracotta", 2, "red_terracotta", 2, "brown_terracotta", 1)

	override fun defaultTalus(): List<BlockState> = B.list("red_sand", "red_sand", "coarse_dirt", "red_terracotta")

	override fun defaultMaxRelief(): Int = 18

	override fun create(site: Site): Landform? {
		val r = site.rng
		val rad = r.range(radius)
		val e = r.range(elongation)
		val phi = r.angle()
		val h = r.range(height)
		val ring = site.ring(rad * 1.4, 8)
		if (!site.reliefOk(ring)) {
			return null
		}
		val base = Site.mean(ring, site.floor0)
		val top = min(base + h, site.ceiling().toDouble()).toInt()
		val inst = Instance(site, base, top, rad, e, phi)
		if (r.chance(thumbs.toDouble())) {
			val a = r.angle()
			val rt = rad * r.range(0.17, 0.25)
			val ht = (top - base) * r.range(0.82, 0.97)
			// March out along a to the outline, then stand the thumb astride it.
			var b = 0.0
			var s = 0.0
			while (s < rad * 3) {
				val qs = inst.q(site.x + Math.cos(a) * s, site.z + Math.sin(a) * s)
				if (qs[0] > 1) {
					b = s
					break
				}
				s += 0.5
			}
			if (b > rt * 2) {
				inst.thumb(site.x + Math.cos(a) * (b - rt * 0.45), site.z + Math.sin(a) * (b - rt * 0.45), rt, base + ht.toInt())
			}
		}
		return inst
	}

	internal inner class Instance(s: Site, private val base: Int, private val top: Int, private val rad: Double, e: Double, phi: Double) :
		Landform(s, maxReach()) {
		private val sqrtE = sqrt(e)
		private val cosPhi = Math.cos(phi)
		private val sinPhi = Math.sin(phi)
		private val cliffBottom = base + Math.round((top - base) * (1.0 - cliff)).toInt()
		private val pedRun = pedRun((top - base).toDouble())
		private val talusW = 4 + rad * 0.15
		private val pedPalette: StrataPalette = pedestalPalette.orElseGet {
			B.strata(null, 0, "red_terracotta", 2, "brown_terracotta", 1, "red_terracotta", 1, "light_gray_terracotta", 1)
		}
		private var tx = Double.NaN
		private var tz = 0.0
		private var tr = 0.0
		private var tTop = 0

		fun thumb(x: Double, z: Double, r: Double, t: Int) {
			tx = x
			tz = z
			tr = r
			tTop = t
		}

		override fun describe(): String = "$NAME at $cx,$cz: r=${Math.round(rad)} h=${top - base}${if (tx.isNaN()) "" else " +thumb"}"

		/** {q (1 = cliff outline), signed distance outside the outline in blocks, world angle}. */
		fun q(px: Double, pz: Double): DoubleArray {
			val dx = px - cx
			val dz = pz - cz
			val amp = rad * roughness * 0.7
			val f = 1 / (rad * 0.9)
			val wx = dx + amp * LNoise.noise2(seed xor 0x11, px * f, pz * f)
			val wz = dz + amp * LNoise.noise2(seed xor 0x12, px * f, pz * f)
			val u = (wx * cosPhi + wz * sinPhi) / sqrtE
			val v = (-wx * sinPhi + wz * cosPhi) * sqrtE
			val d = sqrt(u * u + v * v)
			val th = atan2(v, u)
			val rth = rad * (1 + roughness * (0.6 * LNoise.angular(seed xor 0x13, th, 5.0) + 0.4 * LNoise.angular(seed xor 0x14, th, 14.0)))
			return doubleArrayOf(d / rth, d - rth, atan2(dz, dx))
		}

		override fun column(x: Int, z: Int, c: Col) {
			val px = x + 0.5
			val pz = z + 0.5
			val qs = q(px, pz)
			val q = qs[0]
			val out = max(0.0, qs[1])
			var capY = top + Math.round(0.8 * LNoise.noise2(seed xor 0x15, x / 9.0, z / 9.0)).toInt()
			if (dome > 0) {
				capY -= Math.round(dome * (top - base) * 0.5 * min(1.0, q).pow(2)).toInt()
			}
			var inCleft = false
			if (clefts > 0 && q < 1.02) {
				val cl = abs(LNoise.noise2(seed xor 0x16, px / (rad * 0.55), pz / (rad * 0.55)) + 0.12 * LNoise.noise2(seed xor 0x17, px / 6.0, pz / 6.0))
				val w = clefts * 0.09
				if (cl < w) {
					val floorC = cliffBottom - ((cliffBottom - base) * 0.4).toInt()
					capY = floorC + ((capY - floorC) * (cl / w).pow(3)).toInt()
					inCleft = true
				}
			}
			var mainTop = if (q <= 1.06) erodedTop(q, cliffBottom, capY, 0.05, 0.03, seed xor 0x18, 3.5) else Col.NONE
			if (mainTop != Col.NONE && mainTop < cliffBottom) {
				mainTop = Col.NONE
			}
			var thumbTop = Col.NONE
			if (!tx.isNaN()) {
				val dx = px - tx
				val dz = pz - tz
				val dt = sqrt(dx * dx + dz * dz)
				val th = atan2(dz, dx)
				val rt = tr * (1 + 0.2 * LNoise.angular2(seed xor 0x19, th, 4.0))
				if (dt < rt + 2.6 && mainTop != Col.NONE) {
					// Notch around the thumb: the body drops to a low saddle there, so the thumb stands free.
					mainTop = min(mainTop, cliffBottom + ((top - cliffBottom) * 0.28).toInt())
				}
				if (dt / rt <= 1.06) {
					val tc = tTop + Math.round(0.8 * LNoise.noise2(seed xor 0x1A, x / 4.0, z / 4.0)).toInt()
					val t = erodedTop(dt / rt, cliffBottom, tc, 0.06, 0.15, seed xor 0x1B, 3.0)
					if (t >= cliffBottom) {
						thumbTop = t
					}
				}
			}
			val pedTop: Int = if (q <= 1) {
				cliffBottom
			} else if (out < pedRun) {
				val k = 1 - out / pedRun
				val gully = (1 - min(1.0, abs(LNoise.angular(seed xor 0x1C, qs[2] + 0.25 * LNoise.noise2(seed xor 0x1D, px / 14, pz / 14), 30.0)) * 1.7)).pow(4)
				val ph = (cliffBottom - base) * k.pow(1.35) * (1 - 0.3 * gully * (1 - k)) + 0.8 * LNoise.noise2(seed xor 0x1E, px / 5, pz / 5)
				base + Math.round(ph).toInt()
			} else if (out < pedRun + talusW) {
				val k = 1 - (out - pedRun) / talusW
				base + Math.round(2 * k * (0.6 + 0.4 * LNoise.noise2(seed xor 0x1F, px / 3, pz / 3))).toInt()
			} else {
				Col.NONE
			}
			val body = max(mainTop, thumbTop)
			if (body != Col.NONE && body >= pedTop) {
				c.top = body
				c.kind = K_BODY
				c.cap = !inCleft && body >= (if (thumbTop == body) tTop - 2 else capY - 1) && (q < 0.96 || thumbTop == body)
			} else if (pedTop != Col.NONE) {
				c.top = pedTop
				c.kind = if (out < pedRun) K_PEDESTAL else K_TALUS
			}
		}

		override fun block(x: Int, y: Int, z: Int, depth: Int, c: Col, wet: Boolean): BlockState {
			if (c.kind == K_PEDESTAL) {
				if (depth == 0 && !wet && LNoise.noise2(seed xor 0x21, x / 4.0, z / 4.0) > -0.1) {
					return talusBlock(x, y, z)
				}
				return pedPalette.strataAt(y)
			}
			return super.block(x, y, z, depth, c, wet)
		}
	}

	companion object {
		internal const val NAME = "butte"

		internal val CODEC: MapCodec<ButteShape> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Span.CODEC.optionalFieldOf("radius", Span.of(14.0, 28.0)).forGetter { it.radius },
				Span.CODEC.optionalFieldOf("elongation", Span.of(1.0, 2.0)).forGetter { it.elongation },
				Span.CODEC.optionalFieldOf("height", Span.of(30.0, 60.0)).forGetter { it.height },
				Codec.floatRange(0.1f, 1f).optionalFieldOf("cliff", 0.55f).forGetter { it.cliff },
				Codec.floatRange(0f, 4f).optionalFieldOf("pedestal", 1f).forGetter { it.pedestal },
				Codec.floatRange(0f, 1.5f).optionalFieldOf("dome", 0f).forGetter { it.dome },
				Codec.floatRange(0f, 1f).optionalFieldOf("thumbs", 0f).forGetter { it.thumbs },
				Codec.floatRange(0f, 1f).optionalFieldOf("clefts", 0f).forGetter { it.clefts },
				Codec.floatRange(0f, 0.6f).optionalFieldOf("roughness", 0.22f).forGetter { it.roughness },
				StrataPalette.CODEC.optionalFieldOf("pedestal_palette").forGetter { it.pedestalPalette },
			).apply(i) { radius, elongation, height, cliff, pedestal, dome, thumbs, clefts, roughness, pedestalPalette ->
				ButteShape(radius, elongation, height, cliff, pedestal, dome, thumbs, clefts, roughness, pedestalPalette)
			}
		}
	}
}
