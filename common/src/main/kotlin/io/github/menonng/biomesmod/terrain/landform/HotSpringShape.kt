package io.github.menonng.biomesmod.terrain.landform

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.menonng.biomesmod.terrain.util.StrataPalette
import net.minecraft.world.level.block.state.BlockState
import java.util.Optional
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
 * `hot_spring`: a geothermal pool on a low sinter apron. The pool is deepest and bluest in the middle; around it, bands
 * of coloured microbial mats (`bands`, inner to outer) streak outward along radial runoff channels (Grand Prismatic
 * Spring). With `pools` > 1 it becomes a field of small acid pools with salt chimneys (Dallol: give yellow/green/white
 * bands). Outlines are lobed, never circles.
 */
class HotSpringShape internal constructor(
	internal val pools: Int,
	internal val poolRadius: Span,
	internal val bandWidth: Float,
	internal val spread: Span,
	internal val depth: Int,
	internal val bands: Optional<List<BlockState>>,
	internal val chimneys: Float,
) : LandformShape() {
	private val bandList: List<BlockState> by lazy {
		bands.orElseGet { B.list("light_blue_terracotta", "lime_terracotta", "yellow_terracotta", "orange_terracotta", "red_terracotta", "brown_terracotta") }
	}

	override fun name(): String = NAME

	override fun maxReach(): Int = ceil((if (pools > 1) spread.max else 0f) + poolRadius.max * 1.3 + bandWidth * 7 + 6).toInt()

	override fun defaultPalette(): StrataPalette = B.strata("white_terracotta", 1, "calcite", 2, "diorite", 1, "calcite", 2)

	override fun defaultMaxRelief(): Int = 10

	override fun create(site: Site): Landform? {
		val r = site.rng
		val ring = site.ring((poolRadius.max + bandWidth * 6).toDouble(), 8)
		if (!site.reliefOk(ring)) {
			return null
		}
		val base = max(site.sea, Site.mean(ring, site.floor0))
		val inst = Instance(site, base, pools)
		val s = r.range(spread)
		for (k in 0 until pools) {
			val a = r.angle()
			val d = if (pools == 1) 0.0 else s * sqrt(r.next())
			val rad = r.range(poolRadius) * (if (pools > 1) r.range(0.4, 1.0) else 1.0)
			inst.pool(k, site.x + cos(a) * d, site.z + sin(a) * d, rad)
		}
		return inst
	}

	internal inner class Instance(s: Site, private val base: Int, n: Int) : Landform(s, maxReach()) {
		private val px = DoubleArray(n)
		private val pz = DoubleArray(n)
		private val pr = DoubleArray(n)

		init {
			holdsWater = true
		}

		fun pool(k: Int, x: Double, z: Double, r: Double) {
			px[k] = x
			pz[k] = z
			pr[k] = r
		}

		override fun column(x: Int, z: Int, c: Col) {
			val fx = x + 0.5
			val fz = z + 0.5
			var best = Double.MAX_VALUE // normalised distance to the nearest pool rim (0 centre, 1 rim)
			var bestA = 0.0
			for (k in px.indices) {
				val dx = fx - px[k]
				val dz = fz - pz[k]
				val a = atan2(dz, dx)
				val rr = pr[k] * (1 + 0.18 * LNoise.angular(seed xor (0xC1L + k), a, 5.0) + 0.07 * LNoise.angular(seed xor (0xD1L + k), a, 13.0))
				val q = sqrt(dx * dx + dz * dz) / rr
				if (q < best) {
					best = q
					bestA = a
				}
			}
			val nb = bandList.size
			val bandsOut = bandWidth.toDouble() * nb // in blocks beyond the rim (scaled by pool radius below)
			val rimR = pr[0]
			val outside = (best - 1) * rimR
			// Radial runoff streaks push the bands further out in fingers.
			val streak = max(0.0, LNoise.angular(seed xor 0xE1, bestA + 0.2 * LNoise.noise2(seed xor 0xE2, fx / 9, fz / 9), 18.0))
			val reach = bandsOut * (0.8 + 1.4 * streak * streak)
			if (best < 1) {
				c.top = base - 1 - Math.round((depth - 1) * (1 - best).pow(0.7)).toInt()
				c.carve = true
				c.water = base
				c.kind = K_BAND
				c.aux = 0 // innermost band colours the pool bottom
				c.paint = 1
			} else if (outside < reach + 3) {
				c.top = base
				c.carve = true
				c.paint = 1
				val band = floor(outside / max(0.5, reach) * nb).toInt()
				if (band < nb) {
					c.kind = K_BAND
					c.aux = max(0, min(nb - 1, band))
				} else {
					c.kind = K_FLOOR
				}
				if (chimneys > 0 && outside > 1 && LNoise.unit(seed xor 0xE3, x, z) < chimneys * 0.04) {
					c.top = base + 1 + (LNoise.unit(seed xor 0xE4, x, z) * 3).toInt()
					c.kind = K_CHIMNEY
				}
			}
		}

		override fun block(x: Int, y: Int, z: Int, depth: Int, c: Col, wet: Boolean): BlockState {
			if (c.kind == K_BAND && depth == 0) {
				return bandList[c.aux]
			}
			if (c.kind == K_CHIMNEY) {
				return B.of("white_terracotta")
			}
			return palette.blockAt(y, depth)
		}
	}

	companion object {
		internal const val NAME = "hot_spring"
		internal const val K_BAND = 23
		internal const val K_CHIMNEY = 24

		internal val CODEC: MapCodec<HotSpringShape> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Codec.intRange(1, 30).optionalFieldOf("pools", 1).forGetter { it.pools },
				Span.CODEC.optionalFieldOf("pool_radius", Span.of(10.0, 18.0)).forGetter { it.poolRadius },
				Codec.floatRange(0.5f, 6f).optionalFieldOf("band_width", 2.2f).forGetter { it.bandWidth },
				Span.CODEC.optionalFieldOf("spread", Span.of(10.0, 30.0)).forGetter { it.spread },
				Codec.intRange(1, 12).optionalFieldOf("depth", 5).forGetter { it.depth },
				BlockState.CODEC.listOf().optionalFieldOf("bands").forGetter { it.bands },
				Codec.floatRange(0f, 1f).optionalFieldOf("chimneys", 0f).forGetter { it.chimneys },
			).apply(i) { pools, poolRadius, bandWidth, spread, depth, bands, chimneys ->
				HotSpringShape(pools, poolRadius, bandWidth, spread, depth, bands, chimneys)
			}
		}
	}
}
