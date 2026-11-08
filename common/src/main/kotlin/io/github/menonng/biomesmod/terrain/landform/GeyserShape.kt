package io.github.menonng.biomesmod.terrain.landform

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.menonng.biomesmod.compat.LandformCompat
import io.github.menonng.biomesmod.terrain.util.StrataPalette
import net.minecraft.core.Direction
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.state.BlockState
import java.util.Optional
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `geyser`: a geyser cone (Old Faithful, Castle Geyser): a beaded, terraced mound of pearly sinter with a live geyser
 * vent on top, on a wide wet apron. In 26.2 the vent is vanilla's: a water-filled pipe four blocks deep over potent
 * sulfur on magma, erupting about once a minute up to 24 blocks; in 1.21.1 it is the mod's geyser vent block (every one
 * to three minutes). Runoff channels fan out from the cone,
 * coloured by temperature (white sinter, then yellow, orange and finally green microbial mats), with shallow rimstone
 * pools; fumaroles steam on the apron and dead "bobby-sock" pines (trunks bleached white at the foot by silica) stand
 * at its edge.
 */
class GeyserShape internal constructor(
	internal val coneRadius: Span,
	internal val coneHeight: Span,
	internal val apronRadius: Span,
	internal val mats: Optional<List<BlockState>>,
	internal val pools: Float,
	internal val fumaroles: Int,
	internal val snags: Int,
) : LandformShape() {
	private val matList: List<BlockState> by lazy {
		mats.orElseGet { B.list("biomesmod:geyserite", "biomesmod:thermal_mat_yellow", "biomesmod:thermal_mat_orange", "biomesmod:thermal_mat_green") }
	}

	override fun name(): String = NAME

	override fun maxReach(): Int = ceil(apronRadius.max * 1.3 + 3).toInt()

	override fun defaultPalette(): StrataPalette = StrataPalette(Optional.empty(), 0, listOf(
		StrataPalette.Layer(listOf(StrataPalette.Weighted(B.of("biomesmod:geyserite"), 6), StrataPalette.Weighted(B.of("calcite"), 2),
			StrataPalette.Weighted(B.of("white_terracotta"), 2)), 2),
		StrataPalette.Layer(listOf(StrataPalette.Weighted(B.of("biomesmod:geyserite"), 5), StrataPalette.Weighted(B.of("diorite"), 2)), 1),
	), 0, 0.8, 12.0, 0.5)

	override fun defaultMaxRelief(): Int = 8

	override fun create(site: Site): Landform? {
		val r = site.rng
		val ar = r.range(apronRadius)
		val ring = site.ring(ar, 8)
		if (!site.reliefOk(ring)) {
			return null
		}
		val base = max(site.sea + 1, Site.mean(ring, site.floor0))
		return Instance(site, base, r.range(coneRadius), r.range(coneHeight), ar)
	}

	internal inner class Instance(s: Site, private val base: Int, private val cr: Double, private val ch: Double, private val ar: Double) :
		Landform(s, maxReach()) {
		private val ventTop = base + Math.round(ch).toInt()
		private val vanilla: Array<BlockState>? = LandformCompat.geyserVent()

		init {
			holdsWater = pools > 0
		}

		override fun describe(): String = "$NAME at $cx,$cz: cone r=${Math.round(cr)} h=${Math.round(ch)} apron ${Math.round(ar)}"

		/** Radial runoff channel strength (0..1) at an angle, meandering with distance. */
		private fun channel(a: Double, d: Double): Double {
			val v = LNoise.angular(seed xor 0x91, a + 0.35 * LNoise.noise1(seed xor 0x92, d / 9), 7.0)
			return LNoise.smoothstep(0.15, 0.85, v)
		}

		override fun column(x: Int, z: Int, c: Col) {
			val fx = x + 0.5
			val fz = z + 0.5
			val dx = fx - cx
			val dz = fz - cz
			val a = atan2(dz, dx)
			val lobe = 1 + 0.18 * LNoise.angular(seed xor 0x93, a, 4.0) + 0.08 * LNoise.angular(seed xor 0x94, a, 9.0)
			val d = sqrt(dx * dx + dz * dz)
			val dn = d / lobe
			if (dn > ar * 1.15) {
				return
			}
			if (dn < cr) {
				// Cone: beaded terraces stepping up to the vent.
				val k = dn / cr
				var h = ch * (1 - k.pow(1.4))
				h += 0.6 * LNoise.noise2(seed xor 0x95, fx / 2.5, fz / 2.5)
				val step = floor(h / 1.0 + 0.35 * LNoise.noise2(seed xor 0x96, fx / 4.0, fz / 4.0))
				c.top = base + max(0, step.toInt())
				c.kind = K_CONE
				c.carve = true
				c.paint = 2
				if (d < 0.75) {
					c.kind = K_VENT
					if (vanilla != null) {
						// The vent pipe: four water blocks over the potent sulfur, brimming at the cone top.
						c.top = ventTop - PIPE
						c.water = ventTop
					} else {
						c.top = ventTop
					}
				} else if (vanilla != null && d < 1.9) {
					c.top = max(c.top, ventTop)
				}
				return
			}
			val t = (dn - cr) / max(1.0, ar - cr)
			c.top = base
			c.carve = true
			c.paint = 2
			c.kind = K_APRON
			val chn = channel(a, dn)
			// Temperature falls along the channels: which mat band this column shows.
			val band = LNoise.clamp(t * 1.25 + 0.25 * (1 - chn) + 0.1 * LNoise.noise2(seed xor 0x97, fx / 6, fz / 6), 0.0, 0.999)
			c.aux = floor(band * matList.size).toInt()
			if (dn > ar) {
				// Ragged edge: mats peter out into the surrounding ground.
				val fade = (dn - ar) / (ar * 0.15)
				if (LNoise.unit(seed xor 0x98, x, z) < fade) {
					c.top = Col.NONE
					return
				}
			}
			// Shallow rimstone pools along the warm part of the channels.
			if (pools > 0 && chn > 0.55 && t > 0.1 && t < 0.75) {
				val pn = LNoise.noise2(seed xor 0x99, fx / 3.2, fz / 3.2)
				if (pn > 1 - pools) {
					c.top = base - 1
					c.water = base
					c.kind = K_POOL
				}
			}
		}

		override fun block(x: Int, y: Int, z: Int, depth: Int, c: Col, wet: Boolean): BlockState {
			when (c.kind) {
				K_VENT -> {
					val v = vanilla
					if (v != null) {
						if (depth == 0) return v[1]
						if (depth == 1) return v[0]
					} else if (depth == 0) {
						return VENT
					}
				}
				K_CONE -> if (depth == 0 && LNoise.unit(seed xor 0x9A, x, y, z) < 0.12) return WHITE
				K_APRON, K_POOL -> if (depth == 0) return matList[min(c.aux, matList.size - 1)]
			}
			return palette.strataAt(x, y, z)
		}

		override fun sealBlock(x: Int, y: Int, z: Int): BlockState = GEYSERITE

		override fun decorate(w: ChunkWriter, grid: Array<Col?>) {
			super.decorate(w, grid)
			// Fumaroles on the apron.
			for (k in 0 until fumaroles) {
				val a = LNoise.unit(seed xor (0xA0L + k)) * Math.PI * 2
				val dd = cr + 2 + LNoise.unit(seed xor (0xB0L + k)) * (ar - cr - 3)
				val x = floor(cx + cos(a) * dd).toInt()
				val z = floor(cz + sin(a) * dd).toInt()
				if (w.inside(x, z)) {
					val c = grid[ChunkWriter.gridIndex(x - w.x0, z - w.z0)]
					if (c != null && c.kind == K_APRON && c.top != Col.NONE) {
						w.set(x, w.top(x, z), z, STEAM)
					}
				}
			}
			// Bobby-sock snags: dead pines with silica-bleached feet at the apron edge.
			for (k in 0 until snags) {
				val a = LNoise.unit(seed xor (0xC0L + k)) * Math.PI * 2
				val dd = ar * (0.8 + 0.25 * LNoise.unit(seed xor (0xD0L + k)))
				val x = floor(cx + cos(a) * dd).toInt()
				val z = floor(cz + sin(a) * dd).toInt()
				if (!w.inside(x, z)) {
					continue
				}
				val y0 = w.top(x, z) + 1
				if (!w.get(x, y0, z).isAir) {
					continue
				}
				val h = 4 + (LNoise.unit(seed xor (0xE0L + k)) * 6).toInt()
				for (y in y0 until y0 + h) {
					w.set(x, y, z, if (y < y0 + 2) SOCK else SNAG)
				}
				// One or two broken stubs.
				val by = y0 + h - 2
				val dir = Direction.from2DDataValue((LNoise.unit(seed xor (0xF0L + k)) * 4).toInt())
				if (w.inside(x + dir.stepX, z + dir.stepZ) && w.get(x + dir.stepX, by, z + dir.stepZ).isAir) {
					w.set(x + dir.stepX, by, z + dir.stepZ, SNAG.setValue(RotatedPillarBlock.AXIS, dir.axis))
				}
			}
		}
	}

	companion object {
		internal const val NAME = "geyser"
		internal const val K_CONE = 30
		internal const val K_VENT = 31
		internal const val K_APRON = 32
		internal const val K_POOL = 33
		private const val PIPE = 4
		private val VENT: BlockState by lazy { B.of("biomesmod:geyser_vent") }
		private val STEAM: BlockState by lazy { B.of("biomesmod:steam_vent") }
		private val GEYSERITE: BlockState by lazy { B.of("biomesmod:geyserite") }
		private val WHITE: BlockState by lazy { B.of("white_terracotta") }
		private val SNAG: BlockState by lazy { B.of("stripped_spruce_log") }
		private val SOCK: BlockState by lazy { B.of("biomesmod:ghostwood_log") }

		internal val CODEC: MapCodec<GeyserShape> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Span.CODEC.optionalFieldOf("cone_radius", Span.of(4.0, 8.0)).forGetter { it.coneRadius },
				Span.CODEC.optionalFieldOf("cone_height", Span.of(2.0, 6.0)).forGetter { it.coneHeight },
				Span.CODEC.optionalFieldOf("apron_radius", Span.of(12.0, 20.0)).forGetter { it.apronRadius },
				BlockState.CODEC.listOf().optionalFieldOf("mats").forGetter { it.mats },
				Codec.floatRange(0f, 1f).optionalFieldOf("pools", 0.35f).forGetter { it.pools },
				Codec.intRange(0, 12).optionalFieldOf("fumaroles", 3).forGetter { it.fumaroles },
				Codec.intRange(0, 12).optionalFieldOf("snags", 4).forGetter { it.snags },
			).apply(i) { coneRadius, coneHeight, apronRadius, mats, pools, fumaroles, snags ->
				GeyserShape(coneRadius, coneHeight, apronRadius, mats, pools, fumaroles, snags)
			}
		}
	}
}
