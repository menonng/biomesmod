package io.github.menonng.biomesmod.terrain.landform

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.menonng.biomesmod.terrain.util.StrataPalette
import net.minecraft.core.Direction
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.level.block.state.BlockState
import java.util.Optional
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
 * `karst_island`: a drowned limestone island of tower karst (Ha Long Bay, Phang Nga, El Nido). Not a pillar: an
 * irregular island tens of blocks across whose shore is a cliff standing straight out of the sea, with one to five
 * rounded, forested summits of different heights on a lower shoulder. The sea has cut a notch round the waterline, so
 * the cliff foot overhangs. Some islands hold a lagoon in the middle, open to the sky and reached through a low sea
 * cave; some are pierced by a sea cave from side to side; some have a pocket beach under the cliff.
 */
class KarstIslandShape internal constructor(
	internal val radius: Span,
	internal val height: Span,
	internal val elongation: Span,
	internal val peaks: Int,
	internal val shoulder: Float,
	internal val notch: Int,
	internal val lagoonChance: Float,
	internal val caveChance: Float,
	internal val beachChance: Float,
	internal val beachBlock: BlockState,
	internal val vines: Float,
) : LandformShape() {
	override fun name(): String = NAME

	override fun maxReach(): Int = ceil(radius.max * elongation.max * 1.5 + 8).toInt()

	override fun defaultPalette(): StrataPalette = B.strata(null, 0, "stone", 3, "andesite", 2, "stone", 2, "calcite", 1, "tuff", 1)

	override fun defaultTalus(): List<BlockState> = B.list("gravel", "stone", "mossy_cobblestone")

	override fun defaultBaseRange(): Span = Span.of(-45.0, -1.0) // stands in the sea

	override fun defaultMaxRelief(): Int = 48

	override fun defaultCap(): Optional<Cap> = Optional.of(Cap.grass())

	override fun create(site: Site): Landform? = Instance(site)

	internal inner class Instance(s: Site) : Landform(s, maxReach()) {
		private val r0: Double
		private val h0: Double
		private val elong: Double
		private val ca: Double
		private val sa: Double
		private val n: Int
		private val pu: DoubleArray
		private val pv: DoubleArray
		private val pr: DoubleArray
		private val ph: DoubleArray
		private val lagoon: Boolean
		private val lu: Double
		private val lv: Double
		private val lr: Double
		private val cave: Boolean

		/** Direction (in island coordinates) of the lagoon entrance or of the through cave. */
		private val gu: Double
		private val gv: Double
		private val beach: Boolean
		private val beachA: Double
		private val low: Int

		init {
			val r = s.rng
			r0 = r.range(radius)
			h0 = min(r.range(height) * (0.75 + 0.25 * min(1.0, r0 / 30.0)), (s.ceiling() - s.sea).toDouble())
			elong = r.range(elongation)
			val a = r.angle()
			ca = cos(a)
			sa = sin(a)
			n = max(1, min(peaks, 1 + (r0 * elong / 13.0).toInt()))
			pu = DoubleArray(n)
			pv = DoubleArray(n)
			pr = DoubleArray(n)
			ph = DoubleArray(n)
			for (k in 0 until n) {
				// Summits are strung along the long axis of the island, the way a karst ridge breaks up into cones.
				val t = if (n == 1) 0.0 else (k / (n - 1.0) * 2 - 1) * 0.62
				pu[k] = (t + r.range(-0.12, 0.12)) * r0 * elong
				pv[k] = r.range(-0.3, 0.3) * r0
				pr[k] = r0 * r.range(0.4, 0.66) * (if (n == 1) 1.35 else 1.0)
				ph[k] = if (k == 0) 1.0 else r.range(0.5, 1.0)
			}
			if (n > 1) {
				// The highest summit is not always at the end.
				val j = r.nextInt(n)
				val t = ph[0]
				ph[0] = ph[j]
				ph[j] = t
			}
			lagoon = r0 >= 26 && r.chance(lagoonChance.toDouble())
			lu = r.range(-0.2, 0.2) * r0 * elong
			lv = r.range(-0.15, 0.15) * r0
			lr = r0 * r.range(0.3, 0.4)
			cave = !lagoon && r0 >= 9 && r.chance(caveChance.toDouble())
			val g = if (lagoon) r.angle() else (Math.PI / 2 + r.range(-0.35, 0.35))
			gu = cos(g)
			gv = sin(g)
			beach = r.chance(beachChance.toDouble())
			beachA = r.angle()
			low = min(s.floor0, s.sea - 6)
			border = vines > 0
			holdsWater = false
		}

		override fun describe(): String = "karst island r=%.0f h=%.0f peaks=%d%s%s%s at %d,%d".format(r0, h0, n, if (lagoon) " lagoon" else "",
			if (cave) " cave" else "", if (beach) " beach" else "", cx, cz)

		override fun column(x: Int, z: Int, c: Col) {
			val px = x + 0.5 - cx
			val pz = z + 0.5 - cz
			val u = px * ca + pz * sa
			val v = -px * sa + pz * ca
			val eu = u / (r0 * elong)
			val ev = v / r0
			val a = atan2(ev, eu)
			// The outline: lobed and ragged, never an ellipse.
			val f = 1 + 0.2 * LNoise.angular(seed xor 0x11, a, 4.0) + 0.1 * LNoise.angular(seed xor 0x12, a, 9.0) +
				0.12 * LNoise.noise2(seed xor 0x13, x / 12.0, z / 12.0)
			val q = sqrt(eu * eu + ev * ev) / f
			if (q > 1.45) {
				return
			}
			// Lagoon in the middle: the sea is left as it is, ringed by the inner cliff.
			var dl = 9.0
			if (lagoon) {
				val du = u - lu
				val dv = v - lv
				dl = sqrt(du * du + dv * dv) / (lr * (1 + 0.22 * LNoise.angular(seed xor 0x21, atan2(dv, du), 5.0)))
				if (dl < 1) {
					return
				}
			}
			if (q > 1.06) {
				// Pocket beach at the foot of the cliff on one side.
				if (beach && q < 1.34 && abs(LNoise.angleDiff(a, beachA)) < 0.55) {
					c.top = if (q < 1.2) sea else sea - 1 - ((q - 1.2) / 0.07).toInt()
					c.kind = K_BEACH
				}
				return
			}
			// Height above the sea as a fraction of the full height: a low shoulder with a cliff edge, and the summits.
			var e = shoulder * LNoise.clamp((1 - q) / 0.07, 0.0, 1.0) * (0.75 + 0.35 * LNoise.noise2(seed xor 0x31, x / 17.0, z / 17.0))
			for (k in 0 until n) {
				val du = u - pu[k]
				val dv = v - pv[k]
				val d = sqrt(du * du + dv * dv) / pr[k]
				if (d < 1) {
					e = max(e, ph[k] * (1 - d.pow(3.4)))
				}
			}
			if (lagoon) {
				e = max(e, 0.3 * LNoise.clamp((1.5 - dl) / 0.5, 0.0, 1.0)) // the lagoon is walled all round
			}
			var full = sea + (h0 * e).toInt() + Math.round(1.6 * LNoise.noise2(seed xor 0x32, x / 5.0, z / 5.0)).toInt()
			full = min(full, maxY - 6)
			if (full <= sea) {
				full = sea + 1
			}
			val top = erodedTop(q, low, full, 0.05, 0.07, seed xor 0x41, 4.0)
			if (top < sea) {
				return
			}
			c.top = top
			c.kind = K_BODY
			c.cap = top >= sea + 3 && q < 0.97
			// Sea cave: into the lagoon, or right through the island.
			if (lagoon || cave) {
				val du = if (lagoon) u - lu else u
				val dv = if (lagoon) v - lv else v
				val along = du * gu + dv * gv
				val across = abs(-du * gv + dv * gu)
				val w = 2.6 + 0.8 * LNoise.noise1(seed xor 0x51, along / 7.0)
				if (across < w && (cave || along > 0)) {
					if (top < sea + 5) {
						c.cap = false
						c.top = Col.NONE // too low for a roof: an open channel
						return
					}
					c.voidLo = sea - 3
					c.voidHi = sea + 1 + Math.round(2.2 * (1 - (across / w) * (across / w))).toInt()
					c.voidWater = sea - 1
					return
				}
			}
			// The notch the sea cuts round the waterline: the cliff foot overhangs.
			if (notch > 0 && top > sea + notch + 2 && (q > 0.9 || dl < 1.12)) {
				c.voidLo = sea - 1
				c.voidHi = sea - 1 + notch
				c.voidWater = sea - 1
			}
		}

		override fun block(x: Int, y: Int, z: Int, depth: Int, c: Col, wet: Boolean): BlockState {
			if (c.kind == K_BEACH) {
				return beachBlock
			}
			// Dark, wet rock in the splash zone; moss on the faces above it.
			if (y <= sea + 1 && y >= sea - 2 && LNoise.unit(seed xor 0x61, x, y, z) < 0.5) {
				return TUFF
			}
			if (depth > 0 && y > sea + 2 && LNoise.noise2(seed xor 0x62, (x + z) / 6.0, y / 9.0) > 0.45 && LNoise.unit(seed xor 0x63, x, y, z) < 0.6) {
				return MOSS
			}
			return super.block(x, y, z, depth, c, wet)
		}

		override fun decorate(w: ChunkWriter, grid: Array<Col?>) {
			placeCapFeatures(w, grid)
			if (vines <= 0) {
				return
			}
			// Curtains of creepers hang over the cliff edges.
			for (dz in 0 until 16) {
				for (dx in 0 until 16) {
					val x = w.x0 + dx
					val z = w.z0 + dz
					val here = grid[ChunkWriter.gridIndex(dx, dz)]
					val ground = max(w.top(x, z), sea - 1)
					for (dir in DIRS) {
						val nb = grid[ChunkWriter.gridIndex(dx + dir.stepX, dz + dir.stepZ)] ?: continue
						if (nb.kind != K_BODY || nb.top == Col.NONE || nb.top < ground + 5) {
							continue
						}
						val h = LNoise.hash(seed xor 0x71E5, x * 4 + dir.ordinal, z)
						if (LNoise.unit(h) > vines) {
							continue
						}
						val state = Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(dir), true)
						var y = nb.top
						val len = 2 + (LNoise.unit(h xor 0x77) * 9).toInt()
						var placed = 0
						while (placed < len && y > ground + 1) {
							if ((here != null && here.top != Col.NONE && y <= here.top) || !w.get(x, y, z).isAir) {
								break
							}
							w.set(x, y, z, state)
							placed++
							y--
						}
					}
				}
			}
		}
	}

	companion object {
		internal const val NAME = "karst_island"
		internal const val K_BEACH = 5
		private val DIRS = arrayOf(Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST)
		private val TUFF = B.of("tuff")
		private val MOSS = B.of("moss_block")

		internal val CODEC: MapCodec<KarstIslandShape> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Span.CODEC.optionalFieldOf("radius", Span.of(14.0, 40.0)).forGetter { it.radius },
				Span.CODEC.optionalFieldOf("height", Span.of(24.0, 60.0)).forGetter { it.height },
				Span.CODEC.optionalFieldOf("elongation", Span.of(1.0, 2.0)).forGetter { it.elongation },
				Codec.intRange(1, 6).optionalFieldOf("peaks", 4).forGetter { it.peaks },
				Codec.floatRange(0f, 1f).optionalFieldOf("shoulder", 0.3f).forGetter { it.shoulder },
				Codec.intRange(0, 4).optionalFieldOf("notch", 2).forGetter { it.notch },
				Codec.floatRange(0f, 1f).optionalFieldOf("lagoon_chance", 0.3f).forGetter { it.lagoonChance },
				Codec.floatRange(0f, 1f).optionalFieldOf("cave_chance", 0.3f).forGetter { it.caveChance },
				Codec.floatRange(0f, 1f).optionalFieldOf("beach_chance", 0.35f).forGetter { it.beachChance },
				BlockState.CODEC.optionalFieldOf("beach_block", B.of("sand")).forGetter { it.beachBlock },
				Codec.floatRange(0f, 1f).optionalFieldOf("vines", 0.25f).forGetter { it.vines },
			).apply(i) { radius, height, elongation, peaks, shoulder, notch, lagoonChance, caveChance, beachChance, beachBlock, vines ->
				KarstIslandShape(radius, height, elongation, peaks, shoulder, notch, lagoonChance, caveChance, beachChance, beachBlock, vines)
			}
		}
	}
}
