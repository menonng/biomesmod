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
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * `caldera`: a collapsed volcano. An irregular, uneven ring rim (steep inner wall, gentle outer flank) around a floor
 * that is
 * - `lake`: a crater lake below the rim (Lake Koltsevoye), optionally with a central cone island (Krenitsyn);
 * - `sea`: sunk below sea level and breached on one side so the sea floods in (Ushishir's Kraternaya Bay);
 * - `sand`: a flat sand sea (Tengger), optionally with a cone standing on it (Batok, Bromo).
 *
 * The rim radius and crest height change continuously around the ring, so it never reads as a circle.
 */
class CalderaShape internal constructor(
	internal val radius: Span,
	internal val rimHeight: Span,
	internal val flank: Span,
	internal val floor: String,
	internal val floorDepth: Int,
	internal val cone: Float,
	internal val coneRadius: Span,
	internal val coneHeight: Span,
	internal val breachWidth: Float,
	internal val floorBlock: Optional<BlockState>,
	internal val green: Float,
	internal val coneOffset: Span,
) : LandformShape() {
	override fun name(): String = NAME

	override fun maxReach(): Int = ceil(radius.max * 1.25 + flank.max + 4).toInt()

	override fun defaultPalette(): StrataPalette = B.strata(null, 0, "tuff", 3, "basalt", 2, "andesite", 2, "tuff", 2, "blackstone", 1)

	override fun defaultBaseRange(): Span = Span.of(-60.0, 200.0)

	override fun defaultMaxRelief(): Int = 400

	override fun create(site: Site): Landform? {
		val r = site.rng
		val rad = r.range(radius)
		val base = Site.mean(site.ring(rad, 8), site.floor0)
		val foot = if (floor == "sea") base else site.riseBase(base)
		val crest = min(max(foot, site.sea) + r.range(rimHeight), site.ceiling().toDouble()).toInt()
		val inst = Instance(site, base, foot, crest, rad, r.range(flank), r.angle())
		if (r.chance(cone.toDouble())) {
			val a = r.angle()
			val off = rad * r.range(coneOffset.min.toDouble(), coneOffset.max.toDouble())
			inst.cone(site.x + Math.cos(a) * off, site.z + Math.sin(a) * off, r.range(coneRadius), r.range(coneHeight))
		}
		return inst
	}

	internal inner class Instance(
		s: Site,
		private val base: Int,
		private val foot: Int,
		private val crest: Int,
		private val rad: Double,
		private val flankW: Double,
		private val breach: Double,
	) : Landform(s, maxReach()) {
		private val floorY: Int
		private val water: Int
		private var ccx = Double.NaN
		private var ccz = 0.0
		private var ccr = 0.0
		private var cch = 0.0

		init {
			when (floor) {
				"sea" -> {
					floorY = min(base, s.sea - 4) - floorDepth
					water = s.sea - 1
				}
				"sand" -> {
					floorY = foot + max(2, (crest - foot) / 4)
					water = Col.NONE
				}
				else -> {
					val lake = foot + max(3, (crest - foot) / 3)
					floorY = lake - floorDepth
					water = lake
				}
			}
			holdsWater = water != Col.NONE
		}

		fun cone(x: Double, z: Double, r: Double, h: Double) {
			ccx = x
			ccz = z
			ccr = r
			cch = h
		}

		override fun describe(): String = "$NAME ($floor) at $cx,$cz: r=${Math.round(rad)} crest=$crest${if (ccx.isNaN()) "" else " +cone"}"

		override fun column(x: Int, z: Int, c: Col) {
			val px = x + 0.5
			val pz = z + 0.5
			val dx = px - cx
			val dz = pz - cz
			val a = atan2(dz, dx)
			val d = sqrt(dx * dx + dz * dz)
			val rr = rad * (1 + 0.22 * LNoise.angular(seed xor 0x71, a, 3.0) + 0.08 * LNoise.angular(seed xor 0x72, a, 9.0) +
				0.05 * LNoise.noise2(seed xor 0x78, px / 14, pz / 14))
			var crestH = crest + (crest - foot) * 0.18 * LNoise.angular(seed xor 0x73, a, 4.0)
			val breachK = if (floor == "sea") max(0.0, 1 - abs(LNoise.angleDiff(a, breach)) / breachWidth) else 0.0
			if (breachK > 0) {
				// The breach: the crest drops below the sea so the bay connects to open water.
				crestH += ((water - 3) - crestH) * min(1.0, breachK * 1.6)
			}
			val wall = max(3.0, (crestH - floorY) * 0.35) // inner wall run (steep)
			var top: Double
			var kind = K_BODY
			var inside = false
			var skirt = 1.0
			if (d < rr - wall) {
				inside = true
				val u = d / max(1.0, rr - wall)
				top = floorY + 0.8 * LNoise.noise2(seed xor 0x74, px / 9, pz / 9) + 2.4 * LNoise.noise2(seed xor 0x7B, px / 41, pz / 41) +
					(crestH - floorY) * 0.14 * u * u * u * u
				if (water != Col.NONE) {
					top -= floorDepth * 0.7 * (1 - u * u) // the lake or bay deepens towards the middle
				}
				if (floor == "sand") {
					kind = K_SAND
				}
			} else if (d < rr) {
				val k = (d - (rr - wall)) / wall
				top = floorY + (crestH - floorY) * k.pow(0.6)
				inside = true
			} else if (d < rr + flankW) {
				val g = abs(LNoise.angular(seed xor 0x75, a + 0.2 * LNoise.noise1(seed xor 0x79, d / 12), 26.0)).pow(2)
				val fw = 0.7 + 0.6 * (0.5 + 0.5 * LNoise.angular(seed xor 0x7A, a, 3.0))
				val k = min(1.0, (d - rr) / (flankW * fw))
				// The outer flank runs down to the real floor, so a caldera rising out of the sea (rise_from sea_level) slopes
				// on to the sea bed instead of ending in a drowned step at sea level.
				val low = min(foot, base).toDouble()
				top = low + (crestH - low) * (1 - k).pow(1.4) * (1 - 0.18 * g)
				kind = K_FLANK
				// The foot of the flank runs out into the ground that is really there.
				skirt = LNoise.clamp((1 - k) / 0.4, 0.0, 1.0)
			} else {
				return
			}
			if (!ccx.isNaN()) {
				val cd = hypot(px - ccx, pz - ccz)
				val ca = atan2(pz - ccz, px - ccx)
				val cw = ccr * (1 + 0.12 * LNoise.angular(seed xor 0x76, ca, 4.0))
				if (cd < cw) {
					val t = cd / cw
					val ch = floorY + (if (water != Col.NONE) max(0, water - floorY).toDouble() else 0.0) + cch
					// Radial gullies scored down the cone, deepest on the lower slopes (Krenitsyn's ribbed flanks).
					val gully = abs(LNoise.angular(seed xor 0x7E1, ca + 0.15 * LNoise.noise1(seed xor 0x7E2, cd / 10), 26.0)).pow(2)
					var h = floorY + (ch - floorY) * (1 - t).pow(1.25) * (1 - 0.14 * gully * sqrt(t))
					if (t < 0.18) {
						h -= (0.18 - t) / 0.18 * max(4.0, cch * 0.16) // summit crater
					}
					if (h > top) {
						top = h
						kind = K_CONE
					}
				}
			}
			val ti = Math.round(top).toInt()
			if (inside) {
				c.carve = true
			}
			if (inside && water != Col.NONE && ti < water) {
				c.carve = true
				c.water = water
			} else if (!inside && floor == "sea" && ti < water) {
				// Flank below the sea (the breach and the drowned outer slope): keep it open water, never seal it.
				c.water = water
				c.flag = c.flag or Col.FLAG_NO_SEAL
			}
			c.top = ti
			c.kind = kind
			if (kind == K_FLANK && floor != "sea") {
				c.blend = skirt
			}
		}

		override fun block(x: Int, y: Int, z: Int, depth: Int, c: Col, wet: Boolean): BlockState {
			if (c.kind == K_SAND && depth < 3) {
				return floorBlock.orElse(Blocks.GRAVEL.defaultBlockState())
			}
			val lakeY = if (water != Col.NONE) water else foot
			// Vegetation climbs to a ragged line below the crest; above it bare tephra and rock.
			val greenLine = lakeY + (crest - lakeY) * green + 6 * LNoise.noise2(seed xor 0x7D, x / 19.0, z / 19.0)
			if (c.kind == K_FLANK && depth < 3 && !wet) {
				return if (green > 0 && y < greenLine) pick(GREEN, x, y, z, 0x7E) else pick(FLANK, x, y, z, 0x7C)
			}
			if (c.kind == K_BODY && depth < 2 && !wet && green > 0 && y < greenLine && y > lakeY + 1) {
				// Inner walls: forest and meadow ledges between bare rock faces.
				if (LNoise.noise2(seed xor 0x7F, x / 7.0, (z + y) / 7.0) > -0.2) return pick(GREEN, x, y, z, 0x80)
			}
			if (c.kind == K_CONE && depth == 0 && !wet) {
				val coneGreen = lakeY + cch * green * 0.8 + 4 * LNoise.noise2(seed xor 0x81, x / 13.0, z / 13.0)
				return if (green > 0 && y < coneGreen) pick(GREEN, x, y, z, 0x82) else pick(CONE, x, y, z, 0x77)
			}
			return super.block(x, y, z, depth, c, wet)
		}
	}

	companion object {
		internal const val NAME = "caldera"
		internal const val K_CONE = 21
		internal const val K_SAND = 22
		internal const val K_FLANK = 26
		private val FLANK: List<BlockState> by lazy { B.list("tuff", "gravel", "andesite", "basalt", "coarse_dirt") }
		private val CONE: List<BlockState> by lazy { B.list("biomesmod:volcanic_ash", "biomesmod:scoria", "tuff", "biomesmod:volcanic_ash", "basalt") }
		private val GREEN: List<BlockState> by lazy { B.list("grass_block", "moss_block", "grass_block", "podzol", "coarse_dirt", "grass_block") }

		internal val CODEC: MapCodec<CalderaShape> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Span.CODEC.optionalFieldOf("radius", Span.of(55.0, 85.0)).forGetter { it.radius },
				Span.CODEC.optionalFieldOf("rim_height", Span.of(25.0, 45.0)).forGetter { it.rimHeight },
				Span.CODEC.optionalFieldOf("flank", Span.of(30.0, 50.0)).forGetter { it.flank },
				Codec.STRING.optionalFieldOf("floor", "lake").forGetter { it.floor },
				Codec.intRange(0, 40).optionalFieldOf("floor_depth", 8).forGetter { it.floorDepth },
				Codec.floatRange(0f, 1f).optionalFieldOf("cone", 0f).forGetter { it.cone },
				Span.CODEC.optionalFieldOf("cone_radius", Span.of(14.0, 22.0)).forGetter { it.coneRadius },
				Span.CODEC.optionalFieldOf("cone_height", Span.of(18.0, 32.0)).forGetter { it.coneHeight },
				Codec.floatRange(0.1f, 2f).optionalFieldOf("breach_width", 0.5f).forGetter { it.breachWidth },
				BlockState.CODEC.optionalFieldOf("floor_block").forGetter { it.floorBlock },
				Codec.floatRange(0f, 1f).optionalFieldOf("green", 0f).forGetter { it.green },
				Span.CODEC.optionalFieldOf("cone_offset", Span.of(0.0, 0.35)).forGetter { it.coneOffset },
			).apply(i) { radius, rimHeight, flank, floor, floorDepth, cone, coneRadius, coneHeight, breachWidth, floorBlock, green, coneOffset ->
				CalderaShape(radius, rimHeight, flank, floor, floorDepth, cone, coneRadius, coneHeight, breachWidth, floorBlock, green, coneOffset)
			}
		}
	}
}
