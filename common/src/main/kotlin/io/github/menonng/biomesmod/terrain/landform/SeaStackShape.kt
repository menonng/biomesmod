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
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `sea_stack`: a cluster of stacks, stumps and the occasional arch standing in shallow water off a coast (Cliffs of
 * Moher, Lofoten, Ha Long). Each stack rises from the sea floor with a wave-cut notch near the waterline, banded walls
 * and a ragged, sometimes grassy top. Only placed where the floor is under shallow water.
 */
class SeaStackShape internal constructor(
	internal val clusterRadius: Span,
	internal val count: Int,
	internal val stackRadius: Span,
	internal val height: Span,
	internal val archChance: Float,
	internal val notch: Float,
) : LandformShape() {
	override fun name(): String = NAME

	override fun maxReach(): Int = ceil(clusterRadius.max + stackRadius.max * 2.5 + 4).toInt()

	override fun defaultPalette(): StrataPalette = B.strata(null, 0, "stone", 3, "andesite", 2, "stone", 2, "cobblestone", 1, "tuff", 1)

	override fun defaultTalus(): List<BlockState> = B.list("gravel", "cobblestone", "mossy_cobblestone", "andesite")

	override fun defaultBaseRange(): Span = Span.of(-14.0, -1.0) // only in shallow water

	override fun defaultMaxRelief(): Int = 14

	override fun create(site: Site): Landform? {
		val r = site.rng
		val cr = r.range(clusterRadius)
		val inst = Instance(site, count)
		var placed = 0
		var k = 0
		while (k < count * 3 && placed < count) {
			k++
			val a = r.angle()
			val d = cr * sqrt(r.next())
			val x = site.x + cos(a) * d
			val z = site.z + sin(a) * d
			val f = site.floor(x, z)
			if (f >= site.sea - 1 || f < site.sea - 18) {
				continue // stacks stand in the shallows only
			}
			val rad = r.range(stackRadius)
			val top = min(site.sea + r.range(height).toInt(), site.ceiling())
			inst.stack(placed++, x, z, f, rad, top, r.chance(archChance.toDouble()), r.angle())
		}
		return if (placed == 0) null else inst
	}

	internal inner class Instance(s: Site, count: Int) : Landform(s, maxReach()) {
		private val sx = DoubleArray(count)
		private val sz = DoubleArray(count)
		private val sr = DoubleArray(count)
		private val sf = IntArray(count)
		private val st = IntArray(count)
		private val arch = BooleanArray(count)
		private val archA = DoubleArray(count)
		private var n = 0

		fun stack(k: Int, x: Double, z: Double, floor: Int, r: Double, top: Int, hasArch: Boolean, a: Double) {
			sx[k] = x
			sz[k] = z
			sf[k] = floor
			sr[k] = r
			st[k] = top
			arch[k] = hasArch && r > 3
			archA[k] = a
			n = k + 1
		}

		override fun column(x: Int, z: Int, c: Col) {
			val px = x + 0.5
			val pz = z + 0.5
			for (k in 0 until n) {
				val dx = px - sx[k]
				val dz = pz - sz[k]
				val d = sqrt(dx * dx + dz * dz)
				val a = atan2(dz, dx)
				val rr = sr[k] * (1 + 0.28 * LNoise.angular(seed xor (0x81L + k), a, 5.0))
				val reach = rr * (if (arch[k]) 2.6 else 1.3)
				if (d > reach) {
					continue
				}
				var top: Int
				if (arch[k]) {
					// Elongated fin with a hole through it near the waterline.
					val u = dx * cos(archA[k]) + dz * sin(archA[k])
					val v = -dx * sin(archA[k]) + dz * cos(archA[k])
					val q = sqrt((u / (rr * 2.4)) * (u / (rr * 2.4)) + (v / rr) * (v / rr))
					top = erodedTop(q, sf[k], st[k], 0.06, 0.12, seed xor (0x91L + k), 3.0)
					if (top < sf[k]) {
						continue
					}
					if (abs(u) < rr * 0.9) {
						c.voidLo = sf[k] + 1
						c.voidHi = min(top - 3, sea + (rr * 0.8).toInt())
						c.voidWater = sea - 1
					}
				} else {
					val q = d / rr
					top = erodedTop(q, sf[k], st[k], 0.07, 0.25, seed xor (0x91L + k), 3.0)
					if (top < sf[k]) {
						continue
					}
					// Wave-cut notch just above the waterline makes the stack slightly top-heavy.
					if (notch > 0 && q > 0.75 - 0.3 * notch && top > sea + 2) {
						c.voidLo = sea - 1
						c.voidHi = sea
						c.voidWater = sea - 1
					}
				}
				top += Math.round(LNoise.noise2(seed xor 0xA1, px / 3, pz / 3)).toInt()
				if (c.top == Col.NONE || top > c.top) {
					c.top = top
					c.kind = K_BODY
					c.cap = top >= st[k] - 2
				}
			}
		}
	}

	companion object {
		internal const val NAME = "sea_stack"

		internal val CODEC: MapCodec<SeaStackShape> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Span.CODEC.optionalFieldOf("cluster_radius", Span.of(18.0, 34.0)).forGetter { it.clusterRadius },
				Codec.intRange(1, 24).optionalFieldOf("count", 6).forGetter { it.count },
				Span.CODEC.optionalFieldOf("stack_radius", Span.of(2.0, 6.0)).forGetter { it.stackRadius },
				Span.CODEC.optionalFieldOf("height", Span.of(8.0, 30.0)).forGetter { it.height },
				Codec.floatRange(0f, 1f).optionalFieldOf("arch_chance", 0.25f).forGetter { it.archChance },
				Codec.floatRange(0f, 1f).optionalFieldOf("notch", 0.6f).forGetter { it.notch },
			).apply(i) { clusterRadius, count, stackRadius, height, archChance, notch ->
				SeaStackShape(clusterRadius, count, stackRadius, height, archChance, notch)
			}
		}
	}
}
