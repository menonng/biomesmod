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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `cone`: a stratovolcano with concave flanks cut by radial gullies, a summit crater (dry, lava-filled or a crater
 * lake), parasitic scoria cones low on the flanks and fumaroles on the crater rim. With `rise_from: sea_level` it stands
 * out of the sea from the sea floor (Alaid / Atlasov Island).
 *
 * The surface is never banded: it is dressed by zone and by radial streak. Dark lava flows (blackstone, basalt,
 * scoria) run down from the summit in long tongues, grey ash and red cinder fields lie between them, snow collects in
 * the gullies above the snowline, and the lower flanks green up into moss and tundra. Only crater walls and gully
 * cuts show the layered lava and tephra inside.
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
	internal val parasites: Int,
	internal val vents: Int,
	internal val green: Float,
) : LandformShape() {
	override fun name(): String = NAME

	override fun maxReach(): Int = ceil(radius.max * 1.25 + 4).toInt()

	override fun defaultPalette(): StrataPalette = StrataPalette(Optional.empty(), 0, listOf(
		StrataPalette.Layer(listOf(StrataPalette.Weighted(B.of("basalt"), 5), StrataPalette.Weighted(B.of("blackstone"), 3), StrataPalette.Weighted(B.of("biomesmod:scoria"), 2)), 3),
		StrataPalette.Layer(listOf(StrataPalette.Weighted(B.of("tuff"), 6), StrataPalette.Weighted(B.of("biomesmod:volcanic_ash"), 2), StrataPalette.Weighted(B.of("andesite"), 2)), 2),
		StrataPalette.Layer(listOf(StrataPalette.Weighted(B.of("biomesmod:scoria"), 6), StrataPalette.Weighted(B.of("blackstone"), 4)), 1),
		StrataPalette.Layer(listOf(StrataPalette.Weighted(B.of("andesite"), 5), StrataPalette.Weighted(B.of("basalt"), 3), StrataPalette.Weighted(B.of("tuff"), 2)), 3),
	), 0, 2.5, 18.0, 0.45)

	override fun defaultBaseRange(): Span = Span.of(-80.0, 200.0)

	override fun defaultMaxRelief(): Int = 400 // volcanoes bury whatever is there

	override fun create(site: Site): Landform? {
		val r = site.rng
		val rad = r.range(radius)
		val floor = Site.min(site.ring(rad * 0.5, 6), site.floor0)
		val foot = site.riseBase(floor)
		val summit = min(foot + r.range(height), site.ceiling().toDouble()).toInt()
		val inst = Instance(site, floor, summit, rad, r.range(craterRadius), r.range(craterDepth), r.angle())
		for (k in 0 until parasites) {
			val a = r.angle()
			val d = rad * r.range(0.45, 0.8)
			inst.parasite(k, site.x + cos(a) * d, site.z + sin(a) * d, rad * r.range(0.07, 0.12), r.range(0.45, 0.7))
		}
		return inst
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
		private val px = DoubleArray(parasites)
		private val pz = DoubleArray(parasites)
		private val pr = DoubleArray(parasites)
		private val ph = DoubleArray(parasites)
		private val pb = DoubleArray(parasites)

		init {
			holdsWater = crater != "dry"
		}

		fun parasite(k: Int, x: Double, z: Double, r: Double, steep: Double) {
			px[k] = x
			pz[k] = z
			pr[k] = r
			ph[k] = r * steep
			val dc = sqrt((x - cx) * (x - cx) + (z - cz) * (z - cz))
			pb[k] = floorY + coneH(dc, atan2(z - cz, x - cx), min(1.0, dc / rad))
		}

		override fun describe(): String = "$NAME at $cx,$cz: r=${Math.round(rad)} summit=$summit floor=$floorY"

		/** Main cone height above the floor at a point (no crater); the profile starts at the crater rim. */
		// Every volcano has its own profile: where the steep summit cone breaks into the gentler apron, how high that
		// break sits, how concave the apron is, and two old collapse benches.
		private val breakT = 0.22 + 0.25 * LNoise.unit(seed xor 0x81)
		private val breakH = 0.42 + 0.25 * LNoise.unit(seed xor 0x82)
		private val apronC = concavity * (0.9 + 0.7 * LNoise.unit(seed xor 0x83))
		private val bench1 = 0.35 + 0.25 * LNoise.unit(seed xor 0x84)
		private val bench2 = 0.62 + 0.25 * LNoise.unit(seed xor 0x85)

		private fun coneH(d: Double, a: Double, t: Double): Double {
			val tr = LNoise.clamp((d - cr) / max(1.0, rad - cr), 0.0, 1.0)
			// One side steeper than the other (the summit sits off centre on its base).
			val tb = LNoise.clamp(breakT * (1 + 0.35 * LNoise.angular(seed xor 0x86, a, 2.0)), 0.12, 0.6)
			val prof = if (tr < tb) {
				1 - (1 - breakH) * (tr / tb).pow(1.15)
			} else {
				breakH * (1 - (tr - tb) / (1 - tb)).pow(apronC * (1 + 0.25 * LNoise.angular(seed xor 0x87, a, 3.0)))
			}
			// Collapse benches: short flatter steps across the flank.
			fun bench(at: Double) = 0.035 * LNoise.smoothstep(at - 0.03, at + 0.03, tr) * (0.5 + 0.5 * LNoise.angular(seed xor 0x88, a, 5.0))
			val benches = bench(bench1) + bench(bench2)
			val g = gullies * abs(LNoise.angular(seed xor 0x63, a + 0.25 * LNoise.noise1(seed xor 0x64, d / 14), 34.0)).pow(2)
			val hummocks = 0.025 * LNoise.fbm2(seed xor 0x89, d * Math.cos(a) / 28, d * Math.sin(a) / 28, 3) * sqrt(t)
			return (summit - floorY) * max(0.0, prof - benches + hummocks) * (1 - 0.3 * g * sqrt(t) * (1 - t))
		}

		override fun column(x: Int, z: Int, c: Col) {
			val fx = x + 0.5
			val fz = z + 0.5
			val dx = fx - cx
			val dz = fz - cz
			val a = atan2(dz, dx)
			// Irregular footprint and lumpy flanks.
			val warp = 1 + 0.2 * LNoise.angular(seed xor 0x61, a, 3.0) + 0.06 * LNoise.angular(seed xor 0x62, a, 11.0)
			val d = sqrt(dx * dx + dz * dz) / warp
			if (d >= rad * 1.2) {
				return
			}
			val t = min(1.0, d / rad)
			var h = coneH(d, a, t)
			if (d > rad) {
				h = (summit - floorY) * 0.015 * (1 - (d - rad) / (rad * 0.2))
			}
			h += (0.8 + 1.6 * t) * LNoise.noise2(seed xor 0x65, fx / 9, fz / 9)
			var top = floorY + Math.round(max(0.0, h)).toInt()
			var kind = K_BODY
			// Parasitic scoria cones with their own little craters.
			for (k in 0 until parasites) {
				val pd = sqrt((fx - px[k]) * (fx - px[k]) + (fz - pz[k]) * (fz - pz[k])) / pr[k]
				if (pd < 1) {
					val base = pb[k]
					var ht = base + ph[k] * (1 - pd).pow(1.1)
					if (pd < 0.28) {
						ht = base + ph[k] * (0.72 - 0.25 * (1 - pd / 0.28).pow(2))
					}
					if (ht.toInt() > top) {
						top = ht.toInt()
						kind = K_CINDER
					}
				}
			}
			val rimR = cr * (1 + 0.12 * LNoise.angular(seed xor 0x66, a, 5.0))
			val rawD = sqrt(dx * dx + dz * dz)
			if (rawD < rimR * 1.35) {
				// Crater: bowl inside the rim, rim crest slightly raised, a low notch on one side.
				val k = rawD / rimR
				val notch = max(0.0, 1 - abs(LNoise.angleDiff(a, breach)) / 0.5) * 4.0
				val rim = summit + 1 - Math.round(notch).toInt()
				if (k < 1) {
					// Steep inner walls, a flat floor of rubble.
					top = Math.round(rim - cd * min(1.0, (1 - k) * 2.2).pow(0.8)).toInt()
					c.carve = true
					kind = K_CRATER
					if (crater != "dry" && top < lakeY) {
						c.water = lakeY
						if (crater == "lava") {
							c.fluid = Blocks.LAVA.defaultBlockState()
						}
					}
				} else {
					top = max(top, rim - Math.round((k - 1) * rimR * 0.9).toInt())
				}
			}
			if (top <= floorY && d > rad) {
				return
			}
			c.top = top
			c.kind = kind
			c.aux = (t * 100).toInt()
			if (kind == K_BODY && snowline.isPresent) {
				// Snow lies in the gullies first, on the ridges only well above the snowline.
				// Snow fingers reach far down the gullies; dark lava ridges stay bare well above the snowline.
				val gully = abs(LNoise.angular(seed xor 0x63, a + 0.25 * LNoise.noise1(seed xor 0x64, d / 14), 34.0))
				val ridge = LNoise.angular(seed xor 0x6D, a + 0.12 * LNoise.noise1(seed xor 0x6E, t * 6), 26.0)
				val line = snowline.get() - 30 * gully.pow(3) + 60 * max(0.0, ridge - 0.25) + 8 * LNoise.noise2(seed xor 0x67, fx / 13, fz / 13)
				if (top >= line) {
					c.kind = K_SNOW
				}
			}
		}

		override fun block(x: Int, y: Int, z: Int, depth: Int, c: Col, wet: Boolean): BlockState {
			if (wet || depth >= 3) {
				return if (y <= sea + 1 && y >= sea - 3 && depth < 2) pick(BEACH, x, y, z, 0x6A) else palette.strataAt(x, y, z)
			}
			if (c.kind == K_SNOW && depth <= 1) {
				return if (depth == 0) SNOW else PACKED_ICE
			}
			if (c.kind == K_CRATER) {
				val stain = LNoise.noise2(seed xor 0x6B, x / 4.0, z / 4.0) > 0.45 && LNoise.unit(seed xor 0x6C, x, z) < 0.6
				return if (depth == 0) (if (stain) SULFUR else pick(CRATER, x, y, z, 0x76)) else palette.strataAt(x, y, z)
			}
			if (c.kind == K_CINDER) {
				return pick(CINDER, x, y, z, 0x6C)
			}
			if (y <= sea + 2) {
				return pick(BEACH, x, y, z, 0x6A)
			}
			val t = c.aux / 100.0
			val a = atan2(z + 0.5 - cz, x + 0.5 - cx)
			// Lava tongues: radial streaks that start near the summit and end at their own distance down the flank.
			val tongue = LNoise.angular(seed xor 0x6D, a + 0.12 * LNoise.noise1(seed xor 0x6E, t * 6), 26.0)
			val reachT = 0.45 + 0.5 * (0.5 + 0.5 * LNoise.angular(seed xor 0x6F, a, 9.0))
			if (tongue > 0.45 && t < reachT) {
				return pick(LAVA, x, y, z, 0x70)
			}
			// Low flanks green up; the edge is ragged and follows the gullies.
			val greenLine = 1 - green + 0.12 * LNoise.noise2(seed xor 0x71, x / 17.0, z / 17.0)
			if (green > 0 && t > greenLine) {
				return pick(GREEN, x, y, z, 0x72)
			}
			return if (t < 0.5) pick(ASH_HIGH, x, y, z, 0x73) else pick(ASH_LOW, x, y, z, 0x74)
		}

		override fun decorate(w: ChunkWriter, grid: Array<Col?>) {
			super.decorate(w, grid)
			if (vents <= 0) {
				return
			}
			// Fumaroles on the crater floor and rim.
			for (dz in 0 until 16) {
				for (dx in 0 until 16) {
					val c = grid[ChunkWriter.gridIndex(dx, dz)] ?: continue
					val x = w.x0 + dx
					val z = w.z0 + dz
					val rr = sqrt((x + 0.5 - cx).pow(2) + (z + 0.5 - cz).pow(2))
					if (rr > cr * 1.5 || c.water != Col.NONE) {
						continue
					}
					if (LNoise.unit(seed xor 0x75, x, z) < vents * 0.004) {
						val y = w.top(x, z)
						w.set(x, y, z, VENT)
					}
				}
			}
		}
	}

	companion object {
		internal const val NAME = "cone"
		internal const val K_SNOW = 20
		internal const val K_CRATER = 21
		internal const val K_CINDER = 22
		private val SULFUR: BlockState by lazy { B.of("biomesmod:sulfur_crust") }
		private val VENT: BlockState by lazy { B.of("biomesmod:steam_vent") }
		private val SNOW: BlockState by lazy { Blocks.SNOW_BLOCK.defaultBlockState() }
		private val PACKED_ICE: BlockState by lazy { Blocks.PACKED_ICE.defaultBlockState() }
		private val LAVA: List<BlockState> by lazy { B.list("blackstone", "basalt", "blackstone", "biomesmod:scoria", "basalt", "blackstone") }
		private val ASH_HIGH: List<BlockState> by lazy {
			B.list("biomesmod:volcanic_ash", "biomesmod:scoria", "biomesmod:volcanic_ash", "tuff", "gravel", "biomesmod:volcanic_ash")
		}
		private val ASH_LOW: List<BlockState> by lazy { B.list("biomesmod:volcanic_ash", "tuff", "gravel", "coarse_dirt", "andesite", "biomesmod:volcanic_ash") }
		private val GREEN: List<BlockState> by lazy { B.list("moss_block", "grass_block", "coarse_dirt", "grass_block", "moss_block", "podzol") }
		private val CINDER: List<BlockState> by lazy { B.list("biomesmod:scoria", "biomesmod:volcanic_ash", "biomesmod:scoria", "tuff", "biomesmod:scoria", "blackstone") }
		private val CRATER: List<BlockState> by lazy { B.list("biomesmod:volcanic_ash", "biomesmod:scoria", "gravel", "blackstone", "tuff") }
		private val BEACH: List<BlockState> by lazy { B.list("biomesmod:volcanic_ash", "gravel", "biomesmod:volcanic_ash", "blackstone") }

		internal val CODEC: MapCodec<ConeShape> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Span.CODEC.optionalFieldOf("radius", Span.of(60.0, 95.0)).forGetter { it.radius },
				Span.CODEC.optionalFieldOf("height", Span.of(70.0, 120.0)).forGetter { it.height },
				Codec.floatRange(0.6f, 3f).optionalFieldOf("concavity", 1.35f).forGetter { it.concavity },
				Span.CODEC.optionalFieldOf("crater_radius", Span.of(7.0, 12.0)).forGetter { it.craterRadius },
				Span.CODEC.optionalFieldOf("crater_depth", Span.of(8.0, 14.0)).forGetter { it.craterDepth },
				Codec.STRING.optionalFieldOf("crater", "dry").forGetter { it.crater },
				Codec.floatRange(0f, 1f).optionalFieldOf("gullies", 0.5f).forGetter { it.gullies },
				Codec.INT.optionalFieldOf("snowline").forGetter { it.snowline },
				Codec.intRange(0, 8).optionalFieldOf("parasites", 0).forGetter { it.parasites },
				Codec.intRange(0, 20).optionalFieldOf("vents", 0).forGetter { it.vents },
				Codec.floatRange(0f, 0.8f).optionalFieldOf("green", 0f).forGetter { it.green },
			).apply(i) { radius, height, concavity, craterRadius, craterDepth, crater, gullies, snowline, parasites, vents, green ->
				ConeShape(radius, height, concavity, craterRadius, craterDepth, crater, gullies, snowline, parasites, vents, green)
			}
		}
	}
}
