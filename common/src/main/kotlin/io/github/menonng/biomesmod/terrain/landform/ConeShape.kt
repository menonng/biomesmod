package io.github.menonng.biomesmod.terrain.landform

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.menonng.biomesmod.terrain.util.StrataPalette
import net.minecraft.world.level.block.Blocks
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
 * `cone`: a stratovolcano with concave flanks cut by radial gullies, a summit crater (dry, lava-filled or a crater
 * lake) and an optional snow cap. With `rise_from: sea_level` it stands out of the sea from the sea floor (Alaid /
 * Atlasov Island); on land it is a smaller scoria cone (Batok). The base outline and crest are irregular.
 *
 * @param crater "dry", "lava" or "lake"
 * @param snowline absolute Y above which the flanks are snow-covered (or none)
 */
class ConeShape internal constructor(
	internal val radius: Span,
	internal val height: Span,
	internal val concavity: Float,
	internal val craterRadius: Span,
	internal val craterDepth: Span,
	internal val crater: String,
	internal val gullies: Float,
	internal val snowline: Optional<Int>,
) : LandformShape() {
	override fun name(): String = NAME

	override fun maxReach(): Int = ceil(radius.max * 1.25 + 4).toInt()

	override fun defaultPalette(): StrataPalette = B.strata(null, 0, "basalt", 3, "tuff", 2, "blackstone", 1, "andesite", 2, "tuff", 2)

	override fun defaultBaseRange(): Span = Span.of(-80.0, 200.0)

	override fun defaultMaxRelief(): Int = 400 // volcanoes bury whatever is there

	override fun create(site: Site): Landform? {
		val r = site.rng
		val rad = r.range(radius)
		val floor = Site.min(site.ring(rad * 0.5, 6), site.floor0)
		val foot = site.riseBase(floor)
		val summit = min(foot + r.range(height), site.ceiling().toDouble()).toInt()
		return Instance(site, floor, summit, rad, r.range(craterRadius), r.range(craterDepth), r.angle())
	}

	internal inner class Instance(
		s: Site,
		private val floorY: Int,
		private val summit: Int,
		private val rad: Double,
		private val cr: Double,
		private val cd: Double,
		private val breach: Double,
	) : Landform(s, maxReach()) {
		private val lakeY = summit - Math.round(cd * 0.55).toInt()

		init {
			holdsWater = crater != "dry"
		}

		override fun describe(): String = "$NAME at $cx,$cz: r=${Math.round(rad)} summit=$summit floor=$floorY"

		override fun column(x: Int, z: Int, c: Col) {
			val px = x + 0.5
			val pz = z + 0.5
			val dx = px - cx
			val dz = pz - cz
			val a = atan2(dz, dx)
			// Irregular footprint and lumpy flanks.
			val warp = 1 + 0.16 * LNoise.angular(seed xor 0x61, a, 4.0) + 0.06 * LNoise.angular(seed xor 0x62, a, 11.0)
			val d = sqrt(dx * dx + dz * dz) / warp
			if (d >= rad * 1.2) {
				return
			}
			val t = min(1.0, d / rad)
			val prof = (1 - t).pow(concavity.toDouble())
			// Radial gullies, deeper low on the flank, and a gentle skirt beyond the nominal radius.
			val g = gullies * abs(LNoise.angular(seed xor 0x63, a + 0.25 * LNoise.noise1(seed xor 0x64, d / 14), 30.0)).pow(2)
			var h = (summit - floorY) * prof * (1 - 0.22 * g * sqrt(t))
			if (d > rad) {
				h = (summit - floorY) * 0.02 * (1 - (d - rad) / (rad * 0.2))
			}
			h += 1.0 * LNoise.noise2(seed xor 0x65, px / 7, pz / 7) * t
			var top = floorY + Math.round(max(0.0, h)).toInt()
			val rimR = cr * (1 + 0.12 * LNoise.angular(seed xor 0x66, a, 5.0))
			val rawD = sqrt(dx * dx + dz * dz)
			if (rawD < rimR * 1.35) {
				// Crater: bowl inside the rim, rim crest slightly raised, a low notch on one side.
				val k = rawD / rimR
				val notch = max(0.0, 1 - abs(LNoise.angleDiff(a, breach)) / 0.5) * 2.5
				val rim = summit + 1 - Math.round(notch).toInt()
				if (k < 1) {
					top = Math.round(summit - cd * (1 - k * k)).toInt()
					c.carve = true
					if (crater != "dry" && top < lakeY) {
						c.water = lakeY
						if (crater == "lava") {
							c.fluid = Blocks.LAVA.defaultBlockState()
						}
					}
				} else {
					top = max(top, rim - Math.round((k - 1) * 3).toInt())
				}
			}
			if (top <= floorY && d > rad) {
				return
			}
			c.top = top
			c.kind = if (snowline.isPresent && top >= snowline.get() + Math.round(3 * LNoise.noise2(seed xor 0x67, px / 11, pz / 11)).toInt()) K_SNOW else K_BODY
		}

		override fun block(x: Int, y: Int, z: Int, depth: Int, c: Col, wet: Boolean): BlockState {
			if (c.kind == K_SNOW && depth <= 1 && !wet) {
				return if (depth == 0) Blocks.SNOW_BLOCK.defaultBlockState() else Blocks.PACKED_ICE.defaultBlockState()
			}
			if (depth < 3 && !wet && !c.carve) {
				// Loose scoria and ash streaked down the slope: no horizontal bands on a smooth cone.
				return pick(FLANK, x + (3 * LNoise.noise2(seed xor 0x68, x / 5.0, z / 5.0)).toInt(), y / 4, z, 0x69)
			}
			return super.block(x, y, z, depth, c, wet)
		}
	}

	companion object {
		internal const val NAME = "cone"
		internal const val K_SNOW = 20
		internal val FLANK: List<BlockState> = B.list("tuff", "gravel", "basalt", "andesite", "tuff", "coarse_dirt")

		internal val CODEC: MapCodec<ConeShape> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Span.CODEC.optionalFieldOf("radius", Span.of(60.0, 95.0)).forGetter { it.radius },
				Span.CODEC.optionalFieldOf("height", Span.of(70.0, 120.0)).forGetter { it.height },
				Codec.floatRange(0.6f, 3f).optionalFieldOf("concavity", 1.55f).forGetter { it.concavity },
				Span.CODEC.optionalFieldOf("crater_radius", Span.of(7.0, 12.0)).forGetter { it.craterRadius },
				Span.CODEC.optionalFieldOf("crater_depth", Span.of(8.0, 14.0)).forGetter { it.craterDepth },
				Codec.STRING.optionalFieldOf("crater", "dry").forGetter { it.crater },
				Codec.floatRange(0f, 1f).optionalFieldOf("gullies", 0.5f).forGetter { it.gullies },
				Codec.INT.optionalFieldOf("snowline").forGetter { it.snowline },
			).apply(i) { radius, height, concavity, craterRadius, craterDepth, crater, gullies, snowline ->
				ConeShape(radius, height, concavity, craterRadius, craterDepth, crater, gullies, snowline)
			}
		}
	}
}
