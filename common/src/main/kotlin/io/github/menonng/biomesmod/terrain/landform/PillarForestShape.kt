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
 * `pillar_forest`: a cluster of tall, thin, fluted pillars with flat vegetated caps (Zhangjiajie), or with `dome`,
 * `notch` and `rise_from: sea_level` rounded karst towers standing in water (Ha Long Bay).
 */
class PillarForestShape internal constructor(
	internal val clusterRadius: Span,
	internal val pillarSpacing: Float,
	internal val density: Float,
	internal val pillarRadius: Span,
	internal val height: Span,
	internal val taper: Float,
	internal val flute: Float,
	internal val dome: Float,
	internal val notch: Int,
	internal val talusScale: Float,
) : LandformShape() {
	override fun name(): String = NAME

	private fun talusWidth(r: Double): Double = if (talusScale <= 0) 0.0 else (2 + r * 0.9) * talusScale

	override fun maxReach(): Int {
		val r = pillarRadius.max * (1 + flute) * 1.15
		return ceil(clusterRadius.max * 1.3 + pillarSpacing + r + talusWidth(pillarRadius.max.toDouble()) + 2).toInt()
	}

	override fun defaultPalette(): StrataPalette =
		B.strata(null, 0, "sandstone", 3, "andesite", 1, "smooth_sandstone", 2, "tuff", 1, "sandstone", 2, "stone", 1)

	override fun defaultTalus(): List<BlockState> = B.list("coarse_dirt", "gravel", "cobblestone", "mossy_cobblestone")

	override fun defaultMaxRelief(): Int = 30

	override fun defaultCap(): Optional<Cap> = Optional.of(Cap.grass())

	internal class Pillar(
		val x: Double, val z: Double, val r: Double, val base: Int, val top: Int, val seed: Long, val talusW: Double, val talusH: Double,
		val box: Double,
	)

	override fun create(site: Site): Landform? {
		val r = site.rng
		val rc = r.range(clusterRadius)
		val ring = site.ring(rc * 0.8, 8)
		if (!site.reliefOk(ring)) {
			return null
		}
		val outlineSeed = site.sub(11)
		val pillars = ArrayList<Pillar>()
		val ps = pillarSpacing.toDouble()
		val n = ceil(rc * 1.3 / ps).toInt() + 1
		var reach = 0
		for (j in -n..n) {
			for (i in -n..n) {
				// Offset rows (hex-like) plus strong jitter: no grid alignment survives.
				val px = site.x + (i + 0.5 * (j and 1)) * ps + (r.next() - 0.5) * ps * 0.85
				val pz = site.z + j * ps * 0.866 + (r.next() - 0.5) * ps * 0.85
				val roll = r.next()
				val rad = r.range(pillarRadius)
				val hh = r.range(height)
				val hj = r.next()
				val ps2 = r.nextLong()
				val dx = px - site.x
				val dz = pz - site.z
				val d = sqrt(dx * dx + dz * dz)
				val th = atan2(dz, dx)
				val edge = rc * (1 + 0.3 * LNoise.angular2(outlineSeed, th, 4.0))
				val q = d / edge
				val p = density * (1 - LNoise.smoothstep(0.5, 1.0, q))
				if (roll >= p) {
					continue
				}
				val base = site.floor(px, pz)
				val rise = site.riseBase(base)
				// Taller towards the cluster centre, but every pillar differs.
				val h = hh * (0.55 + 0.45 * (1 - q * q)) * (0.85 + 0.3 * hj)
				val top = min(rise + h, site.ceiling().toDouble()).toInt()
				if (top < base + 6) {
					continue
				}
				val tw = talusWidth(rad)
				val th2 = talusScale * min(9.0, 2 + (top - base) * 0.08)
				val box = rad * (1 + flute) * 1.15 + tw + 1
				pillars.add(Pillar(px, pz, rad, base, top, ps2, tw, th2, box))
				reach = max(reach.toDouble(), d + box + 1).toInt()
			}
		}
		if (pillars.isEmpty()) {
			return null
		}
		return Instance(site, reach, pillars)
	}

	internal inner class Instance(s: Site, reach: Int, private val pillars: List<Pillar>) : Landform(s, reach) {
		override fun describe(): String = "$NAME at $cx,$cz: ${pillars.size} pillars"

		override fun column(x: Int, z: Int, c: Col) {
			var best = Col.NONE
			var capCol = false
			var notchCol = false
			var talusTop = Col.NONE
			val px = x + 0.5
			val pz = z + 0.5
			for (p in pillars) {
				val dx = px - p.x
				val dz = pz - p.z
				if (abs(dx) > p.box || abs(dz) > p.box) {
					continue
				}
				val d = sqrt(dx * dx + dz * dz)
				val th = atan2(dz, dx)
				// Vertical flutes: sharp grooves, rounded ribs; plus a coarse irregular outline.
				val nF = max(5.0, Math.PI * 2 * p.r / 2.3)
				val g = abs(LNoise.angular(p.seed, th, nF))
				val rib = 2 * sqrt(min(1.0, g * 1.6)) - 1
				val outline = 1 + flute * (0.45 * rib + 0.55 * LNoise.angular2(p.seed xor 3, th, 3.0))
				val rr = p.r * outline
				val q = d / rr
				if (q <= 1.08) {
					var t = erodedTop(q, p.base, p.top, 0.07, taper.toDouble(), p.seed xor 5, 3.5)
					if (t > p.base) {
						var capY = p.top + Math.round(1.1 * LNoise.noise2(p.seed xor 9, x / 5.0, z / 5.0)).toInt()
						if (dome > 0) {
							val dh = dome * min(p.r * 1.3, 14.0) * min(1.0, q).pow(2.2)
							capY -= Math.round(dh).toInt()
						}
						t = min(t, capY)
						if (t > best) {
							best = t
							capCol = t >= capY - 2 && q < 0.95
							notchCol = notch > 0 && d > rr - notch - 0.3 && p.base < sea - 1 && t > sea + 3
						}
					}
				}
				if (p.talusW > 0) {
					val s = d - p.r
					if (s < p.talusW) {
						val k = 1 - max(0.0, s) / p.talusW
						val h = p.talusH * k * k * (0.6 + 0.4 * (0.5 + 0.5 * LNoise.noise2(p.seed xor 7, x / 3.0, z / 3.0)))
						talusTop = max(talusTop, p.base + Math.round(h).toInt())
					}
				}
			}
			if (best == Col.NONE && talusTop == Col.NONE) {
				return
			}
			if (best != Col.NONE && best >= talusTop) {
				c.top = best
				c.kind = K_BODY
				c.cap = capCol
				if (notchCol) {
					c.voidLo = sea - 1
					c.voidHi = sea + 1
					c.voidWater = sea
				}
			} else {
				c.top = talusTop
				c.kind = K_TALUS
			}
		}
	}

	companion object {
		internal const val NAME = "pillar_forest"

		internal val CODEC: MapCodec<PillarForestShape> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Span.CODEC.optionalFieldOf("cluster_radius", Span.of(22.0, 40.0)).forGetter { it.clusterRadius },
				Codec.floatRange(3f, 64f).optionalFieldOf("pillar_spacing", 9f).forGetter { it.pillarSpacing },
				Codec.floatRange(0f, 1f).optionalFieldOf("density", 0.7f).forGetter { it.density },
				Span.CODEC.optionalFieldOf("pillar_radius", Span.of(2.5, 5.0)).forGetter { it.pillarRadius },
				Span.CODEC.optionalFieldOf("height", Span.of(35.0, 80.0)).forGetter { it.height },
				Codec.floatRange(0f, 0.9f).optionalFieldOf("taper", 0.12f).forGetter { it.taper },
				Codec.floatRange(0f, 1f).optionalFieldOf("flute", 0.22f).forGetter { it.flute },
				Codec.floatRange(0f, 2f).optionalFieldOf("dome", 0f).forGetter { it.dome },
				Codec.intRange(0, 4).optionalFieldOf("notch", 0).forGetter { it.notch },
				Codec.floatRange(0f, 3f).optionalFieldOf("talus", 1f).forGetter { it.talusScale },
			).apply(i) { clusterRadius, pillarSpacing, density, pillarRadius, height, taper, flute, dome, notch, talus ->
				PillarForestShape(clusterRadius, pillarSpacing, density, pillarRadius, height, taper, flute, dome, notch, talus)
			}
		}
	}
}
