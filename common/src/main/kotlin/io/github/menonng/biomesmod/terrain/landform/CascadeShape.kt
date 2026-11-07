package io.github.menonng.biomesmod.terrain.landform

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.menonng.biomesmod.terrain.util.StrataPalette
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `cascade`: a horseshoe of waterfalls (Iguazu, the Devil's Throat). A broad shallow river at ground level pours over
 * an irregular U-shaped lip into a plunge basin cut `drop` blocks into the ground and leaves down a gorge. Wooded
 * islets on the lip split the curtain into many separate falls. Everything is cut into the existing terrain: only low
 * banks right beside the water are raised, so the landform never stands up as a block. The water is real: the upper
 * river's source blocks spill over the edge (ticks are scheduled), so the falls run as soon as the chunk loads.
 */
class CascadeShape internal constructor(
	internal val radius: Span,
	internal val drop: Span,
	internal val islets: Float,
	internal val riverWidth: Span,
	internal val arc: Float,
) : LandformShape() {
	override fun name(): String = NAME

	override fun maxReach(): Int = ceil(radius.max * 2.6 + 6).toInt()

	override fun defaultPalette(): StrataPalette = B.strata(null, 0, "basalt", 3, "smooth_basalt", 1, "tuff", 2, "basalt", 2)

	override fun defaultMaxRelief(): Int = 20

	override fun create(site: Site): Landform? {
		val r = site.rng
		val rad = r.range(radius)
		val ring = site.ring(rad * 1.6, 8)
		if (!site.reliefOk(ring)) {
			return null
		}
		// The upper river runs at the local ground level; the basin is cut below it.
		val upper = min(max(site.sea + 1, Site.mean(ring, site.floor0)), site.ceiling() - 4)
		val lower = upper - Math.round(r.range(drop)).toInt()
		return Instance(site, lower, upper, rad, r.range(riverWidth), r.angle())
	}

	internal inner class Instance(
		s: Site,
		private val lower: Int,
		private val upper: Int,
		private val rad: Double,
		private val riverW: Double,
		private val dir: Double,
	) : Landform(s, maxReach()) {
		private val reachIn: Double = (maxReach() - 4).toDouble()

		init {
			holdsWater = true
		}

		override fun describe(): String = "$NAME at $cx,$cz: r=${Math.round(rad)} drop=${upper - lower}"

		/** -1 outside, 0 upper shelf/banks, 1 lower basin/gorge. out = {u, v, d, distance to the basin/gorge edge}. */
		private fun zone(px: Double, pz: Double, out: DoubleArray): Int {
			val dx = px - cx
			val dz = pz - cz
			val u = dx * cos(dir) + dz * sin(dir) // downstream
			val v = -dx * sin(dir) + dz * cos(dir)
			val d = sqrt(dx * dx + dz * dz)
			val a = atan2(v, u) // 0 = downstream
			out[0] = u
			out[1] = v
			out[2] = d
			if (d > reachIn) {
				return -1
			}
			val lip = rad * (1 + 0.16 * LNoise.angular(seed xor 0xF1, a, 7.0) + 0.06 * LNoise.angular(seed xor 0xF2, a, 19.0))
			val inArc = abs(a) < Math.toRadians(arc.toDouble())
			val gorgeHalf = rad * 0.55 * (1 + 0.25 * LNoise.noise1(seed xor 0xF3, u / 25))
			val toBasin = min(if (inArc) d - lip else Double.MAX_VALUE, if (u > 0 && u < reachIn - 6) abs(v) - gorgeHalf else Double.MAX_VALUE)
			out[3] = toBasin
			if ((inArc && d < lip) || (u > 0 && abs(v) < gorgeHalf && u < reachIn - 6)) {
				return 1
			}
			return 0
		}

		override fun column(x: Int, z: Int, c: Col) {
			val px = x + 0.5
			val pz = z + 0.5
			val p = DoubleArray(4)
			val zone = zone(px, pz, p)
			if (zone < 0) {
				return
			}
			val u = p[0]
			val v = p[1]
			val d = p[2]
			if (zone == 1) {
				// Plunge basin, deepest under the falls, shallowing down the gorge; closed at the far end.
				val end = max(0.0, (u - (reachIn - 14)) / 8)
				c.top = lower - 4 + Math.round(min(3.0, max(0.0, u) / 25) + end * 7).toInt()
				c.carve = true
				if (c.top < lower) {
					c.water = lower
				}
				c.flag = c.flag or Col.FLAG_NO_SEAL // never wall off the falls
				return
			}
			// Upper level: a wide shallow river fanning out to the lip, banks beyond it.
			val riverHalf = riverW * 0.5 * (1 + 0.2 * LNoise.noise1(seed xor 0xF4, u / 30))
			val fanR = rad * 1.9 + 0.3 * riverHalf
			val toRiver = max(if (u < 0) abs(v) - riverHalf else d - fanR, d - (reachIn - 8))
			val river = toRiver < 0
			val islet = LNoise.noise2(seed xor 0xF5, px / 9, pz / 9) + 0.4 * LNoise.noise2(seed xor 0xF6, px / 4, pz / 4)
			if (river && islet < 1 - islets * 1.4) {
				c.top = upper - 2
				c.water = upper
				c.kind = K_SHELF
				c.carve = true // the river cuts into higher ground
			} else if (river || min(toRiver, p[3]) < BANK) {
				// Islets and a low bank beside the water: raised only where the ground is lower, never cut.
				c.top = upper + 1 + Math.round(1.5 * max(0.0, LNoise.noise2(seed xor 0xF7, px / 13, pz / 13))).toInt()
				c.cap = true
			}
		}

		override fun decorate(w: ChunkWriter, grid: Array<Col?>) {
			super.decorate(w, grid)
			// Let the river spill over the lip: tick the shelf water that touches the basin.
			for (dz in 0 until 16) {
				for (dx in 0 until 16) {
					val c = grid[ChunkWriter.gridIndex(dx, dz)]
					if (c == null || c.kind != K_SHELF) {
						continue
					}
					for (k in 0 until 4) {
						val nx = dx + (if (k == 0) 1 else if (k == 1) -1 else 0)
						val nz = dz + (if (k == 2) 1 else if (k == 3) -1 else 0)
						val n = grid[ChunkWriter.gridIndex(nx, nz)]
						if (n != null && (n.flag and Col.FLAG_NO_SEAL) != 0) {
							w.scheduleWaterTick(w.x0 + dx, upper, w.z0 + dz)
							break
						}
					}
				}
			}
		}
	}

	companion object {
		internal const val NAME = "cascade"
		internal const val K_SHELF = 25

		/** Width of the raised bank kept beside the river and the basin; farther out the terrain is left alone. */
		internal const val BANK = 6.0

		internal val CODEC: MapCodec<CascadeShape> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Span.CODEC.optionalFieldOf("radius", Span.of(30.0, 46.0)).forGetter { it.radius },
				Span.CODEC.optionalFieldOf("drop", Span.of(18.0, 28.0)).forGetter { it.drop },
				Codec.floatRange(0f, 1f).optionalFieldOf("islets", 0.35f).forGetter { it.islets },
				Span.CODEC.optionalFieldOf("river_width", Span.of(40.0, 70.0)).forGetter { it.riverWidth },
				Codec.floatRange(40f, 200f).optionalFieldOf("arc", 115f).forGetter { it.arc },
			).apply(i) { radius, drop, islets, riverWidth, arc -> CascadeShape(radius, drop, islets, riverWidth, arc) }
		}
	}
}
