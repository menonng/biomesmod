package io.github.menonng.biomesmod.terrain.landform

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.menonng.biomesmod.terrain.util.StrataPalette
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.VineBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.placement.PlacedFeature
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
 * `pillar_forest`: a cluster of tall sandstone/quartzite pillars (Zhangjiajie), or with `dome`, `notch` and
 * `rise_from: sea_level` rounded karst towers standing in water (Ha Long Bay).
 *
 * Pillars are true 3D bodies, not extruded outlines: each is 1 to 4 overlapping lobes (so the base is one mass and the
 * top breaks into separate spires and steps), every lobe has a jointed polygonal outline (flat faces, sharp corners),
 * the rock is cut into bedding layers of slightly different width (ledges and overhanging lips, undercut notches
 * along the bedding planes), and the whole pillar leans and slips a little from layer to layer. Ledges can carry soil
 * and shrubs, faces vines and dark streaks.
 */
class PillarForestShape internal constructor(internal val g: Geometry, internal val d: Detail) : LandformShape() {
	internal class Geometry(
		val clusterRadius: Span, val pillarSpacing: Float, val density: Float, val pillarRadius: Span, val height: Span,
		val taper: Float, val flute: Float, val dome: Float, val notch: Int, val talusScale: Float,
	)

	internal class Detail(
		val lobes: Int, val joints: Float, val ledges: Float, val lean: Float, val ledgeBlock: Optional<BlockState>,
		val streaks: Optional<List<BlockState>>, val streakDensity: Float, val vines: Float, val ledgeFeature: Optional<Holder<PlacedFeature>>,
		val ledgeChance: Float,
	)

	override fun name(): String = NAME

	private fun talusWidth(r: Double): Double = if (g.talusScale <= 0) 0.0 else (2 + r * 0.9) * g.talusScale

	override fun maxReach(): Int {
		val r = g.pillarRadius.max * 2.7 + g.height.max * d.lean + 3.5
		return ceil(g.clusterRadius.max * 1.3 + g.pillarSpacing + r + talusWidth(g.pillarRadius.max.toDouble()) + 2).toInt()
	}

	override fun defaultPalette(): StrataPalette =
		B.strata(null, 0, "stone", 4, "andesite", 2, "stone", 3, "tuff", 1, "stone", 2, "andesite", 1)

	override fun defaultTalus(): List<BlockState> = B.list("coarse_dirt", "gravel", "cobblestone", "mossy_cobblestone")

	override fun defaultMaxRelief(): Int = 30

	override fun defaultCap(): Optional<Cap> = Optional.of(Cap.grass())

	/** One lobe: centre offset from the pillar axis, base radius, top, jointed outline (radius factor per angle). */
	internal class Lobe(val ox: Double, val oz: Double, val r: Double, val top: Int, val outline: DoubleArray)

	internal class Pillar(
		val x: Double, val z: Double, val r: Double, val base: Int, val top: Int, val seed: Long, val talusW: Double, val talusH: Double,
		val box: Double, val lobes: Array<Lobe>,
		/** Per y - base: axis offset (lean + slip) and width factor (taper, bedding, notches, flare). */
		val offX: DoubleArray, val offZ: DoubleArray, val scale: DoubleArray,
	)

	override fun create(site: Site): Landform? {
		val r = site.rng
		val rc = r.range(g.clusterRadius)
		val ring = site.ring(rc * 0.8, 8)
		if (!site.reliefOk(ring)) {
			return null
		}
		val outlineSeed = site.sub(11)
		val pillars = ArrayList<Pillar>()
		val ps = g.pillarSpacing.toDouble()
		val n = ceil(rc * 1.3 / ps).toInt() + 1
		var reach = 0
		for (j in -n..n) {
			for (i in -n..n) {
				// Offset rows (hex-like) plus strong jitter: no grid alignment survives.
				val px = site.x + (i + 0.5 * (j and 1)) * ps + (r.next() - 0.5) * ps * 0.85
				val pz = site.z + j * ps * 0.866 + (r.next() - 0.5) * ps * 0.85
				val roll = r.next()
				val rad = r.range(g.pillarRadius)
				val hh = r.range(g.height)
				val hj = r.next()
				val ps2 = r.nextLong()
				val dx = px - site.x
				val dz = pz - site.z
				val dist = sqrt(dx * dx + dz * dz)
				val th = atan2(dz, dx)
				val edge = rc * (1 + 0.3 * LNoise.angular2(outlineSeed, th, 4.0))
				val q = dist / edge
				val p = g.density * (1 - LNoise.smoothstep(0.5, 1.0, q))
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
				val pillar = buildPillar(px, pz, rad, base, top, ps2)
				pillars.add(pillar)
				reach = max(reach.toDouble(), dist + pillar.box + 1).toInt()
			}
		}
		if (pillars.isEmpty()) {
			return null
		}
		return Instance(site, reach, pillars)
	}

	private fun buildPillar(px: Double, pz: Double, rad: Double, base: Int, top: Int, seed: Long): Pillar {
		val rng = LNoise.Rng(seed)
		val h = top - base
		// Lobes: the main one on the axis, the others pushed out so they merge low and part high.
		val nl = max(1, min(4, if (d.lobes <= 1) 1 else 1 + rng.nextInt(d.lobes)))
		var lobeTop = top
		val lobes = Array(nl) { k ->
			val main = k == 0
			val a = rng.angle()
			val off = if (main) 0.0 else rad * rng.range(0.55, 0.95)
			val lr = if (main) rad * (if (nl > 1) 0.85 else 1.0) else rad * rng.range(0.45, 0.75)
			val lt = if (main) top else base + (h * rng.range(0.55, 0.97)).toInt()
			lobeTop = max(lobeTop, lt)
			Lobe(cos(a) * off, sin(a) * off, lr, lt, outline(rng, lr))
		}
		// Bedding planes every 4 to 11 blocks; each layer gets its own width and a small slip.
		val scale = DoubleArray(h + 1)
		val offX = DoubleArray(h + 1)
		val offZ = DoubleArray(h + 1)
		val leanA = rng.angle()
		val leanMax = h * d.lean * rng.range(0.3, 1.0)
		var y = 0
		var layerW = 1.0
		var slipX = 0.0
		var slipZ = 0.0
		while (y <= h) {
			val thick = 4 + rng.nextInt(8)
			layerW = LNoise.clamp(layerW * 0.4 + 0.6 * rng.range(0.86, 1.08), 0.8, 1.1)
			slipX = LNoise.clamp(slipX + (rng.next() - 0.5) * 0.9 * d.joints, -1.2, 1.2)
			slipZ = LNoise.clamp(slipZ + (rng.next() - 0.5) * 0.9 * d.joints, -1.2, 1.2)
			for (k in 0 until thick) {
				val yy = y + k
				if (yy > h) {
					break
				}
				val t = yy / h.toDouble()
				// Undercut notch in the two blocks under each bedding plane (the top of the layer below is softer).
				val fromTop = thick - 1 - k
				val notchCut = if (fromTop <= 1 && yy > 3 && yy < h - 2) d.ledges * (if (fromTop == 0) 0.2 else 0.1) else 0.0
				val flare = if (t < 0.1) 0.28 * (1 - t / 0.1).pow(2) else 0.0
				scale[yy] = (1 - g.taper * t.pow(1.2)) * (1 + (layerW - 1) * d.ledges * 2.2) * (1 - notchCut) + flare
				val lean = leanMax * t.pow(1.4)
				offX[yy] = cos(leanA) * lean + slipX
				offZ[yy] = sin(leanA) * lean + slipZ
			}
			y += thick
		}
		val tw = talusWidth(rad)
		val th2 = g.talusScale * min(9.0, 2 + h * 0.08)
		val box = rad * 2.7 + leanMax + 2.4 + tw + 1
		return Pillar(px, pz, rad, base, lobeTop, seed, tw, th2, box, lobes, offX, offZ, scale)
	}

	/** Jointed outline: 5 to 8 corners with their own radius; straight-ish faces between them, plus fine roughness. */
	private fun outline(rng: LNoise.Rng, r: Double): DoubleArray {
		val res = 64
		val nc = 5 + rng.nextInt(4)
		val ca = DoubleArray(nc)
		val cr = DoubleArray(nc)
		val a0 = rng.angle()
		for (k in 0 until nc) {
			ca[k] = a0 + (k + (rng.next() - 0.5) * 0.6) * Math.PI * 2 / nc
			cr[k] = 1 + d.joints * rng.range(-0.22, 0.14)
		}
		val seed = rng.nextLong()
		val nF = max(5.0, Math.PI * 2 * r / 2.3)
		return DoubleArray(res) { i ->
			val a = -Math.PI + Math.PI * 2 * i / res
			// Distance to the polygon edge through the two neighbouring corners (true straight faces).
			var best = Double.MAX_VALUE
			for (k in 0 until nc) {
				val k2 = (k + 1) % nc
				val x1 = cos(ca[k]) * cr[k]
				val z1 = sin(ca[k]) * cr[k]
				val x2 = cos(ca[k2]) * cr[k2]
				val z2 = sin(ca[k2]) * cr[k2]
				// Ray from the origin at angle a against segment (x1,z1)-(x2,z2).
				val dx = cos(a)
				val dz = sin(a)
				val ex = x2 - x1
				val ez = z2 - z1
				val den = dx * ez - dz * ex
				if (abs(den) < 1e-9) {
					continue
				}
				val tRay = (x1 * ez - z1 * ex) / den
				val u = (x1 * dz - z1 * dx) / den
				if (tRay > 0 && u >= -1e-6 && u <= 1 + 1e-6) {
					best = min(best, tRay)
				}
			}
			if (best == Double.MAX_VALUE) {
				best = 1.0
			}
			// Flutes (vertical grooves) and fine roughness on top of the joints.
			val gr = abs(LNoise.angular(seed, a, nF))
			val rib = 2 * sqrt(min(1.0, gr * 1.6)) - 1
			best * (1 + g.flute * 0.35 * rib + 0.05 * LNoise.angular(seed xor 7, a, 13.0))
		}
	}

	internal inner class Instance(s: Site, reach: Int, private val pillars: List<Pillar>) : Landform(s, reach) {
		private val ledgeBlock: BlockState? = d.ledgeBlock.orElse(null)
		private val streaks: List<BlockState> = d.streaks.orElse(listOf())

		init {
			border = d.vines > 0
		}

		override fun describe(): String = "$NAME at $cx,$cz: ${pillars.size} pillars"

		/** Solid flags of one pillar for this column, y - base; false beyond the array. */
		private fun solid(p: Pillar, px: Double, pz: Double, out: BooleanArray): Boolean {
			var any = false
			val h = p.scale.size - 1
			for (yy in 0..h) {
				val y = p.base + yy
				val sc = p.scale[yy]
				val cxp = p.x + p.offX[yy]
				val czp = p.z + p.offZ[yy]
				var inside = false
				for (lb in p.lobes) {
					if (y > lb.top) {
						continue
					}
					// Rounded shoulders on every lobe top (and full domes for karst towers).
					var rs = sc
					val dh = if (g.dome > 0) g.dome * min(lb.r * 1.3, 14.0) else min(2.5, lb.r * 0.5)
					val above = y - (lb.top - dh)
					if (above > 0) {
						val k = above / dh
						rs *= sqrt(max(0.0, 1 - k * k)) * (if (g.dome > 0) 1.0 else 0.35) + (if (g.dome > 0) 0.0 else 0.65)
					}
					if (g.notch > 0 && y >= sea - 1 && y <= sea + 1 && p.base < sea - 1) {
						rs *= max(0.2, 1 - g.notch / max(2.0, lb.r))
					}
					val dx = px - (cxp + lb.ox * sc)
					val dz = pz - (czp + lb.oz * sc)
					val d2 = dx * dx + dz * dz
					val rMax = lb.r * rs * 1.25
					if (d2 > rMax * rMax) {
						continue
					}
					val rMin = lb.r * rs * 0.7
					if (d2 < rMin * rMin) {
						inside = true
						break
					}
					val th = atan2(dz, dx)
					val f = (th + Math.PI) / (Math.PI * 2) * lb.outline.size
					val i0 = f.toInt() % lb.outline.size
					val i1 = (i0 + 1) % lb.outline.size
					val w = f - f.toInt()
					val o = lb.outline[i0] * (1 - w) + lb.outline[i1] * w
					val rr = lb.r * rs * o
					if (d2 < rr * rr) {
						inside = true
						break
					}
				}
				if (inside) {
					out[yy] = true
					any = true
				}
			}
			return any
		}

		override fun column(x: Int, z: Int, c: Col) {
			val px = x + 0.5
			val pz = z + 0.5
			var talusTop = Col.NONE
			var lo = Int.MAX_VALUE
			var hi = Int.MIN_VALUE
			var hits: Array<BooleanArray?>? = null
			var hitPillars: Array<Pillar?>? = null
			var nh = 0
			var capCol = false
			for (p in pillars) {
				val dx = px - p.x
				val dz = pz - p.z
				if (abs(dx) > p.box || abs(dz) > p.box) {
					continue
				}
				val arr = BooleanArray(p.scale.size)
				if (solid(p, px, pz, arr)) {
					if (hits == null) {
						hits = arrayOfNulls(4)
						hitPillars = arrayOfNulls(4)
					}
					if (nh < 4) {
						hits[nh] = arr
						hitPillars!![nh] = p
						nh++
					}
					lo = min(lo, p.base)
					hi = max(hi, p.base + arr.size - 1)
				}
				if (p.talusW > 0) {
					val s = sqrt(dx * dx + dz * dz) - p.r * 1.2
					if (s < p.talusW) {
						val k = 1 - max(0.0, s) / p.talusW
						val h = p.talusH * k * k * (0.6 + 0.4 * (0.5 + 0.5 * LNoise.noise2(p.seed xor 7, x / 3.0, z / 3.0)))
						talusTop = max(talusTop, p.base + Math.round(h).toInt())
					}
				}
			}
			if (nh == 0) {
				if (talusTop != Col.NONE) {
					c.top = talusTop
					c.kind = K_TALUS
				}
				return
			}
			// Union of all pillars (and talus) over [lo, hi]; the top is the highest solid block, every air run below it a gap.
			val span = hi - lo + 1
			val sol = BooleanArray(span)
			for (k in 0 until nh) {
				val p = hitPillars!![k]!!
				val arr = hits!![k]!!
				for (yy in arr.indices) {
					if (arr[yy]) {
						sol[p.base - lo + yy] = true
					}
				}
			}
			if (talusTop != Col.NONE) {
				for (y in lo..min(hi, talusTop)) {
					sol[y - lo] = true
				}
			}
			var top = hi
			while (top >= lo && !sol[top - lo]) {
				top--
			}
			if (top < lo || (talusTop != Col.NONE && top <= talusTop)) {
				c.top = max(talusTop, top)
				c.kind = K_TALUS
				return
			}
			c.top = top
			c.kind = K_BODY
			var y = lo
			while (y < top) {
				if (!sol[y - lo]) {
					val g0 = y
					while (y < top && !sol[y - lo]) {
						y++
					}
					c.addGap(g0, y - 1)
				}
				y++
			}
			for (k in 0 until nh) {
				val p = hitPillars!![k]!!
				if (p.lobes.any { top <= it.top + 1 && top >= it.top - (if (g.dome > 0) 0.6 * g.dome * min(it.r * 1.3, 14.0) else 1.0) }) {
					capCol = true
				}
			}
			c.cap = capCol
		}

		override fun block(x: Int, y: Int, z: Int, depth: Int, c: Col, wet: Boolean): BlockState {
			if (c.kind == K_BODY) {
				val ledge = c.span != c.top
				if (ledge && !wet && ledgeBlock != null) {
					if (depth == 0) {
						return ledgeBlock
					}
				}
				if (streaks.isNotEmpty() && depth > 0) {
					// Dark weathering streaks running down the faces, strongest under ledges and lips.
					val v = StrataPalette.valueNoise(x / 1.3, y / 11.0, z / 1.3, 0x5A7)
					if (v > 1 - d.streakDensity) {
						return pick(streaks, x, y, z, 0x5A8)
					}
				}
			}
			return super.block(x, y, z, depth, c, wet)
		}

		override fun decorate(w: ChunkWriter, grid: Array<Col?>) {
			super.decorate(w, grid)
			if (d.vines > 0) {
				vines(w, grid)
			}
			val feature = d.ledgeFeature.orElse(null)
			if (feature != null && d.ledgeChance > 0) {
				ledgePlants(w, grid, feature)
			}
		}

		/** Vine curtains hanging down the faces from under ledges. */
		private fun vines(w: ChunkWriter, grid: Array<Col?>) {
			for (dz in 0 until 16) {
				for (dx in 0 until 16) {
					val x = w.x0 + dx
					val z = w.z0 + dz
					val here = grid[ChunkWriter.gridIndex(dx, dz)]
					val ground = w.top(x, z)
					for (dir in H_DIRS) {
						val n = grid[ChunkWriter.gridIndex(dx + dir.stepX, dz + dir.stepZ)] ?: continue
						if (n.kind != K_BODY || n.top < ground + 4) {
							continue
						}
						val h = LNoise.hash(seed xor 0x71E5, x * 4 + dir.ordinal, z)
						if (LNoise.unit(h) > d.vines * 0.35) {
							continue
						}
						// Start just under a solid block of the wall at a random height, hang down 3 to 14 blocks.
						var y = ground + 2 + ((n.top - ground - 2) * LNoise.unit(h xor 0x55)).toInt()
						val len = 3 + (LNoise.unit(h xor 0x77) * 12).toInt()
						val state = Blocks.VINE.defaultBlockState().setValue(VineBlock.getPropertyForFace(dir), true)
						var placed = 0
						while (placed < len && y > ground) {
							val air = here == null || !here.solidAt(y)
							if (!air || !n.solidAt(y) || !w.get(x, y, z).isAir) {
								if (placed > 0) {
									break
								}
								y--
								continue
							}
							w.set(x, y, z, state)
							placed++
							y--
						}
					}
				}
			}
		}

		/** Shrubs and small trees on the wider ledges. */
		private fun ledgePlants(w: ChunkWriter, grid: Array<Col?>, feature: Holder<PlacedFeature>) {
			for (dz in 0 until 16) {
				for (dx in 0 until 16) {
					val c = grid[ChunkWriter.gridIndex(dx, dz)] ?: continue
					val gaps = c.gaps ?: continue
					if (c.kind != K_BODY) {
						continue
					}
					val x = w.x0 + dx
					val z = w.z0 + dz
					for (k in 0 until c.gapCount) {
						val ledgeY = gaps[k * 2] - 1
						if (gaps[k * 2 + 1] - gaps[k * 2] < 3 || !ChunkWriter.isTerrain(w.get(x, ledgeY, z)) || !w.get(x, ledgeY + 1, z).isAir) {
							continue
						}
						val h = LNoise.hash(seed xor 0x1ED6, x, ledgeY, z)
						if (LNoise.unit(h) < d.ledgeChance) {
							w.placeFeature(feature, x, ledgeY + 1, z, h)
						}
					}
				}
			}
		}
	}

	companion object {
		internal const val NAME = "pillar_forest"
		private val H_DIRS = arrayOf(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)

		private val GEOMETRY: MapCodec<Geometry> = RecordCodecBuilder.mapCodec { i ->
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
			).apply(i) { a, b, c, d, e, f, g, h, k, l -> Geometry(a, b, c, d, e, f, g, h, k, l) }
		}

		private val DETAIL: MapCodec<Detail> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Codec.intRange(1, 4).optionalFieldOf("lobes", 3).forGetter { it.lobes },
				Codec.floatRange(0f, 1f).optionalFieldOf("joints", 0.6f).forGetter { it.joints },
				Codec.floatRange(0f, 1f).optionalFieldOf("ledges", 0.45f).forGetter { it.ledges },
				Codec.floatRange(0f, 0.3f).optionalFieldOf("lean", 0.05f).forGetter { it.lean },
				BlockState.CODEC.optionalFieldOf("ledge_block").forGetter { it.ledgeBlock },
				BlockState.CODEC.listOf().optionalFieldOf("streaks").forGetter { it.streaks },
				Codec.floatRange(0f, 0.6f).optionalFieldOf("streak_density", 0.12f).forGetter { it.streakDensity },
				Codec.floatRange(0f, 1f).optionalFieldOf("vines", 0f).forGetter { it.vines },
				PlacedFeature.CODEC.optionalFieldOf("ledge_feature").forGetter { it.ledgeFeature },
				Codec.floatRange(0f, 1f).optionalFieldOf("ledge_chance", 0.04f).forGetter { it.ledgeChance },
			).apply(i) { a, b, c, d, e, f, g, h, k, l -> Detail(a, b, c, d, e, f, g, h, k, l) }
		}

		internal val CODEC: MapCodec<PillarForestShape> = RecordCodecBuilder.mapCodec { i ->
			i.group(GEOMETRY.forGetter { it.g }, DETAIL.forGetter { it.d }).apply(i) { g, d -> PillarForestShape(g, d) }
		}
	}
}
