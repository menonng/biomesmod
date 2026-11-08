package io.github.menonng.biomesmod.terrain.landform

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.menonng.biomesmod.terrain.util.StrataPalette
import net.minecraft.world.level.block.state.BlockState
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `glacial_lake`: a long cirque lake cut into a mountain valley (Moraine Lake). The water sits at the lowest point of the
 * rim, so on a slope the uphill shore is a steep cut face rising straight from the water and the downhill shore gets a
 * low moraine dam, with a large rockpile at the outlet. The lobed outline is elongated along a random axis; the bowl
 * deepens towards the middle. Coloured by the biome's water colour.
 *
 * @param radius half width of the lake (blocks)
 * @param elongation length / width
 * @param depth deepest point below the water
 * @param pile height of the rockpile at the outlet (0 = none)
 */
class GlacialLakeShape internal constructor(
	internal val radius: Span,
	internal val elongation: Span,
	internal val depth: Span,
	internal val pile: Float,
) : LandformShape() {
	override fun name(): String = NAME

	override fun maxReach(): Int = ceil(radius.max * sqrt(elongation.max.toDouble()) * 1.25 * 1.3 + radius.max * 0.5 + 6).toInt()

	override fun defaultPalette(): StrataPalette = B.strata(null, 0, "stone", 4, "andesite", 2, "stone", 3, "tuff", 1)

	override fun defaultTalus(): List<BlockState> = B.list("gravel", "cobblestone", "stone", "andesite", "mossy_cobblestone")

	override fun defaultMaxRelief(): Int = 120

	override fun create(site: Site): Landform? {
		val r = site.rng
		val rad = r.range(radius)
		val e = r.range(elongation)
		val phi = r.angle()
		// The water level is the lowest point of the outline: the lake overflows there (the outlet).
		val n = 16
		val ring = IntArray(n)
		var low = Int.MAX_VALUE
		var outlet = 0.0
		for (k in 0 until n) {
			val a = Math.PI * 2 * k / n
			val l = rad * (if (k % 2 == 0) sqrt(e) else 1.0) * 1.1
			val f = site.floor(site.x + cos(a) * l, site.z + sin(a) * l)
			ring[k] = f
			if (f < low) {
				low = f
				outlet = a
			}
		}
		// On a slope the uphill shore becomes a cut face; limit how deep it cuts.
		val water = max(max(min(low, site.floor0), site.floor0 - 20), site.sea + 1)
		if (water >= site.ceiling() - 8) {
			return null
		}
		return Instance(site, water, rad, e, phi, outlet, r.range(depth))
	}

	internal inner class Instance(
		s: Site,
		private val water: Int,
		private val rad: Double,
		e: Double,
		phi: Double,
		private val outlet: Double,
		private val deep: Double,
	) : Landform(s, maxReach()) {
		private val sqrtE = sqrt(e)
		private val cosPhi = cos(phi)
		private val sinPhi = sin(phi)
		private val pileX = cx + cos(outlet) * rad * sqrtE * 1.05
		private val pileZ = cz + sin(outlet) * rad * sqrtE * 1.05
		private val pileR = rad * 0.45

		init {
			holdsWater = true
		}

		override fun describe(): String = "$NAME at $cx,$cz: r=${Math.round(rad)} water=$water depth=${Math.round(deep)}"

		/** Normalised distance to the shore (1 = shoreline), lobed and elongated. */
		private fun q(px: Double, pz: Double): Double {
			val dx = px - cx
			val dz = pz - cz
			val u = (dx * cosPhi + dz * sinPhi) / sqrtE
			val v = (-dx * sinPhi + dz * cosPhi) * sqrtE
			val d = sqrt(u * u + v * v)
			val th = atan2(v, u)
			val rr = rad * (1 + 0.14 * LNoise.angular(seed xor 0xE51, th, 5.0) + 0.06 * LNoise.angular(seed xor 0xE52, th, 13.0) +
				0.04 * LNoise.noise2(seed xor 0xE53, px / 11, pz / 11))
			return d / rr
		}

		override fun column(x: Int, z: Int, c: Col) {
			val px = x + 0.5
			val pz = z + 0.5
			val q = q(px, pz)
			if (q < 1) {
				// Bowl: steep near the shore, flat in the middle.
				val bowl = (1 - q * q).pow(0.6)
				c.top = water - 1 - Math.round((deep - 1) * bowl + 0.8 * LNoise.noise2(seed xor 0xE54, px / 7, pz / 7)).toInt()
				c.carve = true
				c.water = water
				c.paint = 2
				c.kind = K_FLOOR
				return
			}
			// Rockpile at the outlet and a low moraine dam along the shore: only raised where the ground is lower.
			val dp = sqrt((px - pileX) * (px - pileX) + (pz - pileZ) * (pz - pileZ)) / pileR
			var top = Col.NONE
			if (pile > 0 && dp < 1) {
				val h = pile * (1 - dp * dp).pow(0.7) * (0.75 + 0.25 * LNoise.noise2(seed xor 0xE55, px / 4, pz / 4))
				top = water + Math.round(h).toInt()
			}
			if (q < 1.22) {
				val dam = water + 1 + Math.round(1.5 * (1 - (q - 1) / 0.22) * (0.5 + 0.5 * LNoise.noise2(seed xor 0xE56, px / 6, pz / 6))).toInt()
				top = max(top, dam)
			}
			if (top != Col.NONE) {
				c.top = top
				c.kind = K_TALUS
			}
		}

		override fun block(x: Int, y: Int, z: Int, depth: Int, c: Col, wet: Boolean): BlockState {
			if (c.kind == K_FLOOR) {
				// Glacial silt and gravel on the lake bed, rock below.
				return if (depth < 2) pick(BED, x, y, z, 0xE57) else palette.strataAt(x, y, z)
			}
			return super.block(x, y, z, depth, c, wet)
		}
	}

	companion object {
		internal const val NAME = "glacial_lake"
		private val BED: List<BlockState> by lazy { B.list("gravel", "clay", "gravel", "stone", "sand") }

		internal val CODEC: MapCodec<GlacialLakeShape> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Span.CODEC.optionalFieldOf("radius", Span.of(60.0, 80.0)).forGetter { it.radius },
				Span.CODEC.optionalFieldOf("elongation", Span.of(1.8, 2.4)).forGetter { it.elongation },
				Span.CODEC.optionalFieldOf("depth", Span.of(8.0, 14.0)).forGetter { it.depth },
				Codec.floatRange(0f, 30f).optionalFieldOf("pile", 10f).forGetter { it.pile },
			).apply(i) { radius, elongation, depth, pile -> GlacialLakeShape(radius, elongation, depth, pile) }
		}
	}
}
