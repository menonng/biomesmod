package io.github.menonng.biomesmod.terrain.landform

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.menonng.biomesmod.terrain.util.StrataPalette
import net.minecraft.world.level.block.state.BlockState
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `monolith`: one huge fluted columnar tower on a talus skirt (Devils Tower), or with `count` 2-5 and `taper` near 1 a
 * group of sharp spires (Tre Cime di Lavaredo, granite horns). Outlines are fluted by `columns` vertical ribs whose
 * depth wanders with height, and walls are eroded in bands, so no two towers share a silhouette.
 */
class MonolithShape internal constructor(
	internal val count: Int,
	internal val spread: Span,
	internal val radius: Span,
	internal val height: Span,
	internal val taper: Float,
	internal val flute: Float,
	internal val columns: Int,
	internal val talusScale: Float,
) : LandformShape() {
	override fun name(): String = NAME

	private fun talusWidth(r: Double): Double = if (talusScale <= 0) 0.0 else r * 0.8 * talusScale + 3

	override fun maxReach(): Int {
		val r = radius.max * (1 + flute) * 1.1
		return ceil((if (count > 1) spread.max else 0f) + r + talusWidth(radius.max.toDouble()) + 3).toInt()
	}

	override fun defaultPalette(): StrataPalette = B.strata(null, 0, "tuff", 4, "andesite", 2, "tuff", 3, "smooth_basalt", 1)

	override fun defaultTalus(): List<BlockState> = B.list("gravel", "andesite", "cobblestone", "tuff", "coarse_dirt")

	override fun defaultMaxRelief(): Int = 20

	override fun create(site: Site): Landform? {
		val r = site.rng
		val ring = site.ring(radius.max * 1.5, 8)
		if (!site.reliefOk(ring)) {
			return null
		}
		val base = site.riseBase(Site.mean(ring, site.floor0))
		val inst = Instance(site, base, count)
		val rowAngle = r.angle()
		val s = r.range(spread)
		for (k in 0 until count) {
			val off = if (count == 1) 0.0 else (k - (count - 1) / 2.0) * s * r.range(0.8, 1.15)
			val side = if (count == 1) 0.0 else r.range(-0.25, 0.25) * s
			val x = site.x + cos(rowAngle) * off - sin(rowAngle) * side
			val z = site.z + sin(rowAngle) * off + cos(rowAngle) * side
			val rad = r.range(radius) * (if (count > 1) r.range(0.75, 1.1) else 1.0)
			val h = min(r.range(height) * (if (count > 1) r.range(0.75, 1.0) else 1.0), (site.ceiling() - base).toDouble()).toInt()
			inst.tower(k, x, z, rad, h, r.next() * Math.PI * 2)
		}
		return inst
	}

	internal inner class Instance(s: Site, private val base: Int, n: Int) : Landform(s, maxReach()) {
		private val tx = DoubleArray(n)
		private val tz = DoubleArray(n)
		private val tr = DoubleArray(n)
		private val th = IntArray(n)
		private val tphase = DoubleArray(n)

		fun tower(k: Int, x: Double, z: Double, r: Double, h: Int, phase: Double) {
			tx[k] = x
			tz[k] = z
			tr[k] = r
			th[k] = h
			tphase[k] = phase
		}

		override fun describe(): String = "$NAME at $cx,$cz: ${tx.size} tower(s), h=${th[0]} r=${Math.round(tr[0])}"

		/** Radius of tower k at angle a and height fraction t (0 base .. 1 top). */
		private fun radiusAt(k: Int, a: Double, t: Double): Double {
			val rib = if (columns <= 0) 0.0 else abs(sin(a * columns * 0.5 + tphase[k] + 0.6 * LNoise.noise1(seed xor (0x31L + k), t * 3)))
			val lobes = 0.08 * LNoise.angular(seed xor (0x41L + k), a, 3.0)
			val shrink = 1 - taper * t - (if (taper > 0.6) 0.25 * t * t else 0.0)
			return tr[k] * (1 + lobes - flute * rib) * max(0.05, shrink)
		}

		override fun column(x: Int, z: Int, c: Col) {
			val px = x + 0.5
			val pz = z + 0.5
			var bodyTop = Col.NONE
			var talusTop = Double.NEGATIVE_INFINITY
			for (k in tx.indices) {
				val dx = px - tx[k]
				val dz = pz - tz[k]
				val d = sqrt(dx * dx + dz * dz)
				val a = atan2(dz, dx)
				val r0 = radiusAt(k, a, 0.0)
				if (d <= r0) {
					// Walk up while the column stays inside the shrinking, fluted outline.
					var top = base
					val h = th[k]
					for (y in 0..h) {
						val t = y / h.toDouble()
						if (d > radiusAt(k, a, t)) {
							break
						}
						top = base + y
					}
					// A slightly domed, broken summit.
					top += Math.round(1.2 * LNoise.noise2(seed xor 0x51, px / 5, pz / 5)).toInt()
					bodyTop = max(bodyTop, top)
				}
				val tw = talusWidth(tr[k])
				if (tw > 0 && d < r0 + tw) {
					val lobe = 0.55 + 0.45 * (0.5 + 0.5 * LNoise.angular(seed xor (0x53L + k), a, 6.0)) + 0.15 * LNoise.noise2(seed xor 0x54, px / 6, pz / 6)
					val kk = 1 - max(0.0, d - r0 * 0.9) / (tw * lobe)
					val skirt = th[k] * 0.22 * talusScale / 1.6 * max(0.0, kk).pow(1.6) + 1.2 * LNoise.noise2(seed xor 0x52, px / 4, pz / 4)
					talusTop = max(talusTop, base + skirt)
				}
			}
			val skirtTop = if (talusTop == Double.NEGATIVE_INFINITY) Col.NONE else Math.round(talusTop).toInt()
			if (bodyTop != Col.NONE && bodyTop >= skirtTop) {
				c.top = bodyTop
				c.kind = K_BODY
			} else if (skirtTop != Col.NONE && skirtTop > base - 1) {
				c.top = skirtTop
				c.kind = K_TALUS
			}
		}
	}

	companion object {
		internal const val NAME = "monolith"

		internal val CODEC: MapCodec<MonolithShape> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Codec.intRange(1, 5).optionalFieldOf("count", 1).forGetter { it.count },
				Span.CODEC.optionalFieldOf("spread", Span.of(14.0, 22.0)).forGetter { it.spread },
				Span.CODEC.optionalFieldOf("radius", Span.of(11.0, 16.0)).forGetter { it.radius },
				Span.CODEC.optionalFieldOf("height", Span.of(55.0, 85.0)).forGetter { it.height },
				Codec.floatRange(0f, 1f).optionalFieldOf("taper", 0.18f).forGetter { it.taper },
				Codec.floatRange(0f, 0.5f).optionalFieldOf("flute", 0.1f).forGetter { it.flute },
				Codec.intRange(0, 80).optionalFieldOf("columns", 36).forGetter { it.columns },
				Codec.floatRange(0f, 4f).optionalFieldOf("talus", 1.6f).forGetter { it.talusScale },
			).apply(i) { count, spread, radius, height, taper, flute, columns, talus ->
				MonolithShape(count, spread, radius, height, taper, flute, columns, talus)
			}
		}
	}
}
