package io.github.menonng.biomesmod.site

import io.github.menonng.biomesmod.worldgen.Placements
import net.minecraft.world.level.biome.Climate
import net.minecraft.world.level.levelgen.DensityFunction
import net.minecraft.world.level.levelgen.synth.NormalNoise
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The sites of one world: a pure function of the world seed and the vanilla climate.
 *
 * The world is cut into square cells of `cell` blocks. Each cell may hold one site, centred at a jittered point; the
 * climate at that point (temperature, humidity, continentalness, erosion) picks a biome among the specs that accept
 * it. Sites never fight: when two would overlap, the one with the higher priority hash keeps its place and the other
 * is dropped (a site can only be dropped by a site that itself survives, so the result is the same whatever order
 * chunks generate in). The outline is a lobed circle, domain-warped by a seeded noise.
 *
 * Used by [SiteDensity] (terrain) and [SiteBiomes] (biome source), which therefore always agree.
 */
class SiteField private constructor(
	val config: SiteConfig,
	private val noise: NormalNoise,
	private val temperature: DensityFunction,
	private val vegetation: DensityFunction,
	private val continents: DensityFunction,
	private val erosion: DensityFunction,
) {
	/** One placed site. [core] is the full-strength radius before the lobes, [ramp] the fade width. */
	data class Site(val x: Int, val z: Int, val core: Double, val ramp: Double, val spec: SiteConfig.Spec, val seed: Long, val priority: Long) {
		fun outer(): Double = core * 1.3 + ramp

		/** Lobed outline: radius multiplier at angle [a]. */
		internal fun shape(a: Double): Double {
			val p1 = (seed and 0xFFFF).toDouble() / 65536.0 * PI * 2
			val p2 = ((seed ushr 16) and 0xFFFF).toDouble() / 65536.0 * PI * 2
			val p3 = ((seed ushr 32) and 0xFFFF).toDouble() / 65536.0 * PI * 2
			return 1 + 0.13 * sin(2 * a + p1) + 0.08 * sin(3 * a + p2) + 0.05 * sin(5 * a + p3)
		}
	}

	/** Up to two contributing sites at a point and their weights (0..1, already normalised to sum at most 1). */
	data class Blend(val a: Site?, val wa: Double, val b: Site?, val wb: Double) {
		fun total(): Double = wa + wb

		companion object {
			@JvmField
			val NONE = Blend(null, 0.0, null, 0.0)
		}
	}

	private val seed: Long = mix(noise.getValue(1234.567, 0.0, -2345.678).toBits()) xor
		mix(noise.getValue(-3456.5, 0.0, 4567.25).toBits() + 0x5EEDL)
	private val reach: Double = config.maxOuter()
	private val raw = ConcurrentHashMap<Long, Any>()
	private val resolved = ConcurrentHashMap<Long, Any>()

	// ---------------------------------------------------------------------------------------------- placement

	/** The surviving site of cell (gx, gz), or null. */
	fun site(gx: Int, gz: Int): Site? {
		val key = cellKey(gx, gz)
		var v = resolved[key]
		if (v == null) {
			val candidate = rawSite(gx, gz)
			var s = candidate
			if (candidate != null) {
				val span = ceil(2 * reach / config.cell).toInt()
				outer@ for (dz in -span..span) {
					for (dx in -span..span) {
						if (dx == 0 && dz == 0) {
							continue
						}
						val o = rawSite(gx + dx, gz + dz)
						if (o != null && o.priority > candidate.priority && overlaps(candidate, o) && site(gx + dx, gz + dz) != null) {
							s = null
							break@outer
						}
					}
				}
			}
			v = resolved.putIfAbsent(key, s ?: NONE) ?: (s ?: NONE)
		}
		return if (v === NONE) null else v as Site
	}

	private fun rawSite(gx: Int, gz: Int): Site? {
		val key = cellKey(gx, gz)
		var v = raw[key]
		if (v == null) {
			val s = computeRaw(gx, gz)
			v = raw.putIfAbsent(key, s ?: NONE) ?: (s ?: NONE)
		}
		return if (v === NONE) null else v as Site
	}

	private fun computeRaw(gx: Int, gz: Int): Site? {
		if (config.specs.isEmpty()) {
			return null
		}
		val h = mix(seed xor mix(gx * GOLDEN + gz * MURMUR))
		if (unit(h) >= config.chance) {
			return null
		}
		val c = config.cell
		val j = config.jitter
		val x = floor((gx + 0.5 + (unit(mix(h + 1)) - 0.5) * j) * c).toInt()
		val z = floor((gz + 0.5 + (unit(mix(h + 2)) - 0.5) * j) * c).toInt()
		val at = DensityFunction.SinglePointContext(x, 64, z)
		val t = temperature.compute(at)
		val hu = vegetation.compute(at)
		val co = continents.compute(at)
		val er = erosion.compute(at)
		val candidates = config.specs.filter { it.accepts(t, hu, co, er) && (!it.island || openSea(x, z, it.coreMax)) }
		if (candidates.isEmpty()) {
			return null
		}
		var pick = unit(mix(h + 3)) * candidates.sumOf { it.weight }
		var spec = candidates.last()
		for (s in candidates) {
			pick -= s.weight
			if (pick < 0) {
				spec = s
				break
			}
		}
		val core = spec.coreMin + unit(mix(h + 4)) * (spec.coreMax - spec.coreMin)
		return Site(x, z, core, spec.ramp, spec, mix(h + 5), mix(h + 6))
	}

	/**
	 * True when (x, z) lies out at sea: the continentalness is below the coast at half or more of 32 points on a ring of
	 * [radius] blocks and one at two thirds of it. Island sites are only placed here; whatever land still reaches into
	 * the ring is cut away by the moat of sea every island site digs round itself (see computeColumn).
	 */
	private fun openSea(x: Int, z: Int, radius: Double): Boolean {
		var sea = 0
		for (ring in 0 until 2) {
			val r = radius * (if (ring == 0) 1.0 else 0.66)
			for (k in 0 until 16) {
				val a = k * PI / 8
				val at = DensityFunction.SinglePointContext((x + kotlin.math.cos(a) * r).toInt(), 64, (z + sin(a) * r).toInt())
				if (continents.compute(at) < OPEN_SEA) {
					sea++
				}
			}
		}
		return sea >= 16
	}

	// ---------------------------------------------------------------------------------------------- sampling

	/** Weight of one site at (x, z) after the domain warp: 1 inside the core, fading to 0 across the ramp. */
	internal fun weight(s: Site, wx: Double, wz: Double): Double {
		val dx = wx - s.x
		val dz = wz - s.z
		val d = sqrt(dx * dx + dz * dz)
		if (d >= s.outer()) {
			return 0.0
		}
		val r = s.core * s.shape(atan2(dz, dx))
		if (d <= r) {
			return 1.0
		}
		val t = (d - r) / s.ramp
		if (t >= 1) {
			return 0.0
		}
		return 1 - t * t * (3 - 2 * t)
	}

	/** The (up to two) sites covering (x, z) with their weights. */
	fun blend(x: Int, z: Int): Blend {
		if (config.specs.isEmpty()) {
			return Blend.NONE
		}
		val wx = x + config.warp * noise.getValue(x.toDouble(), 0.0, z.toDouble())
		val wz = z + config.warp * noise.getValue(x + 5011.0, 0.0, z - 7027.0)
		val c = config.cell
		val gx0 = Math.floorDiv((wx - reach).toInt(), c)
		val gx1 = Math.floorDiv((wx + reach).toInt(), c)
		val gz0 = Math.floorDiv((wz - reach).toInt(), c)
		val gz1 = Math.floorDiv((wz + reach).toInt(), c)
		var a: Site? = null
		var b: Site? = null
		var wa = 0.0
		var wb = 0.0
		for (gz in gz0..gz1) {
			for (gx in gx0..gx1) {
				val s = site(gx, gz) ?: continue
				val w = weight(s, wx, wz)
				if (w <= 0) {
					continue
				}
				if (w > wa) {
					b = a
					wb = wa
					a = s
					wa = w
				} else if (w > wb) {
					b = s
					wb = w
				}
			}
		}
		if (a == null) {
			return Blend.NONE
		}
		val sum = wa + wb
		if (sum > 1) {
			wa /= sum
			wb /= sum
		}
		return Blend(a, wa, b, wb)
	}

	/**
	 * How a site moves the terrain of one column. [delta] is how far the original smooth surface [s0] moves (up or
	 * down) to the new surface [sEff]; above the new surface the original terrain is compressed by [detail] (1 = kept,
	 * small = flattened); [flat] is the weight of the flat plateau/basin profile.
	 */
	class Column(val active: Boolean, val s0: Double, val delta: Double, val detail: Double, val flat: Double) {
		val sEff: Double = s0 + delta

		/** Height in the original terrain that block height y shows. */
		fun mapY(y: Int): Int = if (y <= sEff) SiteDensity.floorInt(y - delta) else SiteDensity.floorInt(s0 + (y - sEff) / detail)

		companion object {
			@JvmField
			val NONE = Column(false, 0.0, 0.0, 1.0, 0.0)
		}
	}

	/** The column at (x, z); [depth] is the router's original depth (per-thread cache, so each column is computed once). */
	fun column(x: Int, z: Int, depth: DensityFunction): Column {
		val cache = COLUMNS.get()
		if (cache.field !== this) {
			cache.reset(this)
		}
		val key = (x.toLong() shl 32) xor (z.toLong() and 0xFFFFFFFFL)
		val slot = (mix(key) and (COLUMN_CACHE - 1).toLong()).toInt()
		val hit = cache.values[slot]
		if (hit != null && cache.keys[slot] == key) {
			return hit
		}
		val c = computeColumn(x, z, depth)
		cache.keys[slot] = key
		cache.values[slot] = c
		return c
	}

	private fun computeColumn(x: Int, z: Int, depth: DensityFunction): Column {
		lastDepth = depth
		val b = blend(x, z)
		val a = b.a
		// Fjords may run on past the edge of their site until they meet open water.
		val beyond = if (hasChannels) channelSitesBeyond(x, z, a, b.b, depth) else emptyList()
		if (a == null && beyond.isEmpty()) {
			return Column.NONE
		}
		val s0 = originalSurface(x, z, depth)
		var delta = 0.0
		var detail = 1.0
		var flat = 0.0
		var bench = 0.0
		for ((site, w) in arrayOf(a to b.wa, b.b to b.wb)) {
			if (site == null || w <= 0) {
				continue
			}
			val spec = site.spec
			if (spec.terrain == SiteConfig.Terrain.KEEP) {
				continue
			}
			// Mountains keep the hills above a fixed reference (low land rises towards the target); the other terrains
			// measure the hills from the site's own mean surface, so the site's typical height is the target.
			val reference = if (spec.terrain == SiteConfig.Terrain.MOUNTAIN) REFERENCE_Y else referenceSurface(site, depth)
			// Ridged peaks (see ridges(): a ridged base with slope-shifted detail): sharp crests and arêtes over the target height.
			// The ranges rise only inside the site (smoothstep of the weight), so the edges stay gentle and blend out.
			val peaks = if (spec.ridges > 0) spec.ridges * ridges(x.toDouble(), z.toDouble()) * smooth01((w - 0.25) / 0.6) else 0.0
			// Soft ceiling: heights approach the configured maximum smoothly instead of being cut flat at the build limit.
			val target = softCap(spec.targetY + spec.relief * (s0 - reference) + peaks)
			val d = if (spec.terrain == SiteConfig.Terrain.MOUNTAIN) max(0.0, target - s0) else target - s0
			if ((spec.terrain == SiteConfig.Terrain.LIFT || spec.terrain == SiteConfig.Terrain.MOUNTAIN) && spec.channels == null) {
				// Only where the site stands well above the sea: a low island keeps the little height it has.
				bench += w * ((spec.targetY - SEA_LEVEL - 10.0) / 20.0).coerceIn(0.0, 1.0)
			}
			if (spec.island) {
				// An island stands alone: its land takes the inner part of the site, and round it the ground is taken down
				// below the sea (wherever it is not already), so a ring of water always parts it from any other land.
				val land = smooth01((w - 0.5) / 0.35)
				val moat = smooth01(w / 0.25) * (1 - land)
				delta += land * d + moat * (kotlin.math.min(s0, SEA_LEVEL - 12.0) - s0)
				detail += land * (max(0.15, spec.relief) - 1) + moat * (0.25 - 1)
				continue
			}
			delta += w * d
			val s = when (spec.terrain) {
				SiteConfig.Terrain.PLATEAU, SiteConfig.Terrain.BASIN -> FLAT_DETAIL
				SiteConfig.Terrain.LIFT -> max(0.15, spec.relief)
				else -> 1.0
			}
			detail += w * (s - 1)
			if (spec.terrain == SiteConfig.Terrain.PLATEAU || spec.terrain == SiteConfig.Terrain.BASIN) {
				flat += w
			}
		}
		// Where a raised site meets the sea its slope would run straight into the water. Round sea level the ground is
		// eased into a shore: a low strand above the waterline and a shallow shelf below it, steepening again further up
		// and further out.
		if (bench > 0) {
			val d = s0 + delta - SEA_LEVEL
			if (d > -BENCH_DOWN && d < BENCH_UP) {
				val band = if (d > 0) BENCH_UP else BENCH_DOWN
				val eased = Math.signum(d) * band * Math.pow(Math.abs(d) / band, 1.7)
				delta += (eased - d) * kotlin.math.min(1.0, bench)
			}
		}
		// Fjords: channels cut from the site centre towards the sea, below sea level (the aquifer floods them).
		val cutters = ArrayList<Site>(2)
		if (a != null && b.wa > 0) cutters.add(a)
		b.b?.let { if (b.wb > 0) cutters.add(it) }
		cutters.addAll(beyond)
		for (site in cutters) {
			val ch = site.spec.channels ?: continue
			val surface = s0 + delta
			val cut = channelSurface(site, ch, x.toDouble(), z.toDouble(), surface, depth)
			if (cut < surface) {
				delta = cut - s0
				val inside = ((surface - cut) / 6.0).coerceIn(0.0, 1.0)
				detail += (1.0 - detail) * inside
				flat *= 1 - inside
			}
		}
		if (delta == 0.0 && detail == 1.0) {
			return Column.NONE
		}
		// Near the build limit the rock above the surface (crags, overhangs) is compressed into the headroom that is
		// left, so peaks taper below the limit instead of being cut flat.
		val headroom = io.github.menonng.biomesmod.BiomesConfig.elevation.maxTargetY + 18.0 - (s0 + delta)
		if (headroom < 60.0) {
			detail = kotlin.math.min(detail, kotlin.math.max(0.05, headroom / 60.0))
		}
		return Column(true, s0, delta, max(FLAT_DETAIL, detail), flat)
	}

	/** Seeded multi-octave noise in [-1, 1] at a coordinate already divided by its scale (used for zone edges). */
	fun detailNoise(x: Double, z: Double, octaves: Int): Double {
		var sum = 0.0
		var amp = 1.0
		var norm = 0.0
		var f = 1.0
		for (o in 0 until octaves) {
			sum += amp * noise.getValue(x * f * 256 + o * 911.3, 300.0 + o * 37, z * f * 256 - o * 517.9)
			norm += amp
			amp *= 0.5
			f *= 2.07
		}
		return (sum / norm).coerceIn(-1.0, 1.0)
	}

	private fun softCap(y: Double): Double {
		val cap = io.github.menonng.biomesmod.BiomesConfig.elevation.maxTargetY + 12.0
		val knee = cap - 60.0
		return if (y <= knee) y else knee + (cap - knee) * kotlin.math.tanh((y - knee) / (cap - knee))
	}

	private fun smooth01(t: Double): Double {
		val c = t.coerceIn(0.0, 1.0)
		return c * c * (3 - 2 * c)
	}

	/** One ridged octave pair in [0, 1] at warped coordinates: thin sharp crests, broad lows. */
	private fun ridgePair(x: Double, z: Double, f: Double, salt: Double): Double {
		val n0 = noise.getValue(x * f + salt, 120.0 + salt * 0.05, z * f - salt * 0.4)
		var r0 = (1.0 - kotlin.math.abs(n0) * 1.7).coerceIn(0.0, 1.0)
		r0 *= r0
		val n1 = noise.getValue(x * f * 2 + salt + 1013.7, 173.0 + salt * 0.05, z * f * 2 - salt * 0.4 - 419.3)
		var r1 = (1.0 - kotlin.math.abs(n1) * 1.7).coerceIn(0.0, 1.0)
		r1 *= r1
		// The finer octave only builds on the crests of the coarser one (a multifractal).
		return (r0 + 0.5 * r1 * (r0 * 1.6).coerceIn(0.0, 1.0)) / 1.5
	}

	/**
	 * Mountain ranges in [0, 1]. A broad ridged base gives the ranges; the detail on top is built the way Tectonic
	 * (MIT licence) builds its mountain ridges: the detail noise is not read where it stands but at a point pushed
	 * away by the steepness of the base (shift = |base slope| x constant, per axis), so the small ridges are dragged
	 * down the flanks into spurs and gullies instead of lying on the range like an even grain; a "weathering" noise
	 * thins the detail out in places. The base, the constants and the ridged shaping are written for this mod.
	 */
	internal fun ridges(x: Double, z: Double): Double {
		// Domain warp so the ranges bend and branch instead of running straight.
		val wx = x + 140 * noise.getValue(x * 0.7 + 1733.0, 50.0, z * 0.7)
		val wz = z + 140 * noise.getValue(x * 0.7, 50.0, z * 0.7 - 2213.0)
		val base = ridgePair(wx, wz, 1.2, 0.0)
		val e = 16.0
		val slopeX = kotlin.math.abs(ridgePair(wx + e, wz, 1.2, 0.0) - ridgePair(wx - e, wz, 1.2, 0.0))
		val slopeZ = kotlin.math.abs(ridgePair(wx, wz + e, 1.2, 0.0) - ridgePair(wx, wz - e, 1.2, 0.0))
		val detail = ridgePair(wx + RIDGE_SHIFT * slopeX, wz + RIDGE_SHIFT * slopeZ, 3.0, 2027.0)
		val weathering = 1.0 - (kotlin.math.abs(noise.getValue(wx * 2.5 - 911.0, 77.0, wz * 2.5 + 577.0)) * 1.6).coerceIn(0.0, 1.0)
		// Detail rides on the ranges (none on the flat lows) and is strongest where the rock is least weathered.
		val onRange = (base * 1.8).coerceIn(0.0, 1.0)
		return ((base + 0.38 * detail * onRange * (0.35 + 0.65 * weathering)) / 1.28 * 1.35).coerceIn(0.0, 1.0)
	}

	/** [angle] of the main fjord, how far from the centre it [reach]es, and whether open [water] was found there. */
	internal class ChannelInfo(val angle: Double, val phase: Double, val phase2: Double, val reach: Double, val water: Boolean)

	private val channelInfos = ConcurrentHashMap<Long, ChannelInfo>()
	private val hasChannels: Boolean = config.specs.any { it.channels != null }

	@Volatile
	private var lastDepth: DensityFunction? = null

	/**
	 * Where the main fjord of a site goes, computed once per site: rays are cast from the site in 48 directions and the
	 * fjord takes the one that reaches open water soonest (the original smooth surface below sea level for 16 blocks:
	 * the sea, or a river valley, whose river then carries it on to the sea). The fjord is cut all the way to that
	 * water, past the edge of the site if it has to, so it never ends in a wall of land. If there is no water within
	 * 1.6 site radii it heads for the lowest ground on the rim, as before.
	 */
	private fun channelInfo(site: Site, depth: DensityFunction): ChannelInfo = channelInfos.computeIfAbsent(cellKey(site.x, site.z)) {
		val outer = site.outer()
		var angle = 0.0
		var reach = Double.MAX_VALUE
		for (k in 0 until 48) {
			val a = k * PI / 24
			val ca = kotlin.math.cos(a)
			val sa = sin(a)
			var run = 0
			var r = outer * 0.45
			while (r < outer * MOUTH_REACH && r < reach) {
				if (originalSurface((site.x + ca * r).toInt(), (site.z + sa * r).toInt(), depth) < SEA_LEVEL - 3) {
					if (++run >= 5) { // 40 blocks of water: the sea or a bay, not a river
						reach = r
						angle = a
						break
					}
				} else {
					run = 0
				}
				r += 8.0
			}
		}
		val water = reach != Double.MAX_VALUE
		if (!water) {
			var best = Double.MAX_VALUE
			val r = outer * 0.92
			for (k in 0 until 24) {
				val a = k * PI / 12
				val h = originalSurface((site.x + kotlin.math.cos(a) * r).toInt(), (site.z + sin(a) * r).toInt(), depth)
				if (h < best) {
					best = h
					angle = a
				}
			}
		}
		ChannelInfo(angle, unit(mix(site.seed + 71)) * PI * 2, unit(mix(site.seed + 73)) * PI * 2,
			if (water) max(reach + 56.0, outer * 1.05) else outer * 1.05, water)
	}

	/** The fjord sites other than [a] and [b] whose main channel runs past their own edge and may reach (x, z). */
	private fun channelSitesBeyond(x: Int, z: Int, a: Site?, b: Site?, depth: DensityFunction): List<Site> {
		val c = config.cell
		val r = (reach * MOUTH_REACH + 160).toInt()
		var out: ArrayList<Site>? = null
		for (gz in Math.floorDiv(z - r, c)..Math.floorDiv(z + r, c)) {
			for (gx in Math.floorDiv(x - r, c)..Math.floorDiv(x + r, c)) {
				val s = site(gx, gz) ?: continue
				val ch = s.spec.channels ?: continue
				if (s === a || s === b) {
					continue
				}
				val d = hypot((x - s.x).toDouble(), (z - s.z).toDouble())
				if (d < s.outer() * 0.9 || d > s.outer() * MOUTH_REACH + 160) {
					continue
				}
				if (d <= channelInfo(s, depth).reach + ch.width * 1.5) {
					if (out == null) out = ArrayList(1)
					out.add(s)
				}
			}
		}
		return out ?: emptyList()
	}

	/** For the self-check: where the main fjord of [site] ends. */
	fun fjordMouth(site: Site): String? {
		val depth = lastDepth ?: return null
		if (site.spec.channels == null) return null
		val info = channelInfo(site, depth)
		return if (info.water) "open water %d blocks from the centre (site edge at %d)".format(info.reach.toInt() - 56, site.outer().toInt())
		else "NO open water within %d blocks; the fjord ends at the site edge".format((site.outer() * MOUTH_REACH).toInt())
	}

	/**
	 * Surface height inside the fjord channels of a site at (x, z), or [surface] outside them. Each channel runs from
	 * near the centre to beyond the site edge on the seaward side, meandering, widening towards the sea, with a steep
	 * U profile whose floor lies [SiteConfig.Channels.depth] below sea level and rises at the fjord head.
	 */
	private fun channelSurface(site: Site, ch: SiteConfig.Channels, x: Double, z: Double, surface: Double, depth: DensityFunction): Double {
		val info = channelInfo(site, depth)
		val outer = site.outer()
		var best = surface
		val headMain = -0.25 * site.core
		val ca = kotlin.math.cos(info.angle)
		val sa = sin(info.angle)
		for (k in 0 until ch.count) {
			val main = k == 0
			val ox: Double
			val oz: Double
			val dir: Double
			val length: Double
			if (main) {
				// The main fjord: from behind the site centre out to the open water that was found.
				ox = site.x + ca * headMain
				oz = site.z + sa * headMain
				dir = info.angle
				length = info.reach - headMain
			} else {
				// An arm: it comes down from inland and joins the main fjord, each at its own stretch of it (so no two
				// meet in one pool) and on alternating sides. It is measured from the junction up to its head.
				val at = info.reach * (0.1 + 0.5 * ((k - 1) + 0.2 + 0.6 * unit(mix(site.seed + 90 + k))) / (ch.count - 1))
				val m = mainMeander(ch, info, at - headMain, info.reach - headMain)
				ox = site.x + ca * at - sa * m
				oz = site.z + sa * at + ca * m
				dir = info.angle + PI + (if (k % 2 == 1) 1.0 else -1.0) * (0.55 + 0.5 * unit(mix(site.seed + 80 + k)))
				length = outer * (0.26 + 0.2 * unit(mix(site.seed + 100 + k)))
			}
			val cx = kotlin.math.cos(dir)
			val cz = sin(dir)
			val px = x - ox
			val pz = z - oz
			val along = px * cx + pz * cz
			if (along < (if (main) -ch.width * 2 else 0.0) || along > length) {
				continue
			}
			val meander = if (main) mainMeander(ch, info, along, length)
				else smooth01(along / 90.0) * ch.width * 0.55 * sin(along / 130.0 + info.phase2 + 2.3 * k)
			val across = -px * cz + pz * cx - meander
			// 1 at the seaward end of a channel, 0 at its head.
			val t = if (main) (along / length).coerceIn(0.0, 1.0) else 1 - along / length
			// The width swells into basins and narrows at sills along the way, and opens out towards the sea.
			val vary = 1 + 0.3 * sin(along / 113.0 + info.phase * 1.7 + 2.1 * k) + 0.18 * sin(along / 47.0 + info.phase2 * 1.3 + k)
			val half = ch.width * vary * (if (main) 0.36 + 0.5 * t * t else 0.2 + 0.16 * t)
			val q = kotlin.math.abs(across) / half
			if (q >= 1.0) {
				continue
			}
			// Fjord head: the floor climbs to the surrounding ground over the last stretch.
			val fromHead = if (main) along else length - along
			val headRise = (fromHead / (ch.width * 3)).coerceIn(0.0, 1.0)
			val deep = ch.depth * (if (main) 1.0 else 0.7) * (0.75 + 0.25 * vary)
			val floor = SEA_LEVEL - deep * headRise + (surface - SEA_LEVEL) * (1 - headRise) * 0.85
			val profile = q * q * q * (0.35 + 0.65 * q)
			best = kotlin.math.min(best, floor + (surface - floor) * profile)
		}
		return best
	}

	/** Sideways swing of the main fjord at [along] blocks from its head; it dies out towards the mouth. */
	private fun mainMeander(ch: SiteConfig.Channels, info: ChannelInfo, along: Double, length: Double): Double =
		smooth01((length - along) / 280.0) * (ch.width * 1.3 * sin(along / 170.0 + info.phase) + ch.width * 0.5 * sin(along / 61.0 + info.phase2))

	private val references = ConcurrentHashMap<Long, Double>()

	/** Mean original surface of a site (its centre and eight points around the core), computed once per site. */
	private fun referenceSurface(site: Site, depth: DensityFunction): Double = references.computeIfAbsent(cellKey(site.x, site.z)) {
		var sum = originalSurface(site.x, site.z, depth)
		for (k in 0 until 8) {
			val a = k * PI / 4
			sum += originalSurface((site.x + kotlin.math.cos(a) * site.core * 0.6).toInt(), (site.z + sin(a) * site.core * 0.6).toInt(), depth)
		}
		sum / 9
	}

	/** Sites whose centre is within [radius] blocks of (x, z), nearest first; optionally only one biome. */
	fun sitesNear(x: Int, z: Int, radius: Int, biome: String?): List<Site> {
		val c = config.cell
		val out = ArrayList<Site>()
		for (gz in Math.floorDiv(z - radius, c)..Math.floorDiv(z + radius, c)) {
			for (gx in Math.floorDiv(x - radius, c)..Math.floorDiv(x + radius, c)) {
				val s = site(gx, gz) ?: continue
				if ((biome == null || s.spec.biome == biome) && hypot((s.x - x).toDouble(), (s.z - z).toDouble()) <= radius) {
					out.add(s)
				}
			}
		}
		out.sortBy { hypot((it.x - x).toDouble(), (it.z - z).toDouble()) }
		return out
	}

	companion object {
		private val NONE = Any()
		private val GOLDEN = 0x9E3779B97F4A7C15uL.toLong()
		private val MURMUR = 0xC2B2AE3D27D4EB4FuL.toLong()
		private val MIX1 = 0xBF58476D1CE4E5B9uL.toLong()
		private val MIX2 = 0x94D049BB133111EBuL.toLong()
		private const val UNIT = 1.0 / 9007199254740992.0 // 2^-53

		private val BY_NOISE: MutableMap<NormalNoise, SiteField> = Collections.synchronizedMap(WeakHashMap())

		@Volatile
		private var samplers: Array<Any?> = arrayOfNulls(0)

		/** Height that `relief` measures the original hills from. */
		private const val REFERENCE_Y = 72.0

		/** Continentalness below which a point counts as open sea (the vanilla ocean band, clear of the coast). */
		private const val OPEN_SEA = -0.19

		/** Sea level of the overworld (fjord floors are cut below it). */
		private const val SEA_LEVEL = 63.0
		private const val BENCH_UP = 10.0
		private const val BENCH_DOWN = 8.0

		/** How far past its site (in site radii) a fjord may run to find open water. */
		private const val MOUTH_REACH = 2.0

		/** Blocks of detail shift per unit of base slope in [ridges]. */
		private const val RIDGE_SHIFT = 110.0

		/** Detail kept above the surface of plateau and basin sites (almost flat). */
		private const val FLAT_DETAIL = 0.06
		private const val COLUMN_CACHE = 1 shl 12
		private val COLUMNS: ThreadLocal<ColumnCache> = ThreadLocal.withInitial { ColumnCache() }

		private class ColumnCache {
			var field: SiteField? = null
			val keys = LongArray(COLUMN_CACHE)
			val values = arrayOfNulls<Column>(COLUMN_CACHE)

			fun reset(f: SiteField) {
				field = f
				values.fill(null)
			}
		}

		/**
		 * The original smooth surface at (x, z): where the router's depth crosses zero. Vanilla depth is linear in y
		 * (offset + a y gradient), so two samples find it exactly; other routers get a good estimate.
		 */
		internal fun originalSurface(x: Int, z: Int, depth: DensityFunction): Double {
			val d1 = depth.compute(DensityFunction.SinglePointContext(x, 0, z))
			val d2 = depth.compute(DensityFunction.SinglePointContext(x, 256, z))
			if (d1 - d2 < 1e-6) {
				return REFERENCE_Y
			}
			return (256 * d1 / (d1 - d2)).coerceIn(-64.0, 400.0)
		}

		/** The field for a wired site density function (one per world: all modes share the same seeded noise). */
		internal fun of(d: SiteDensity): SiteField {
			val n = d.noise.noise()!!
			synchronized(BY_NOISE) {
				return BY_NOISE.getOrPut(n) {
					SiteField(Placements.get().sites.scaled(d.scale), n, d.temperature, d.vegetation, d.continents, d.erosion)
				}
			}
		}

		/**
		 * The field behind a level's climate sampler, found in its depth function (which contains the site offset), or
		 * null when the dimension has no sites (Nether, End, presets whose terrain is not wrapped).
		 */
		fun forSampler(sampler: Climate.Sampler): SiteField? {
			val cache = samplers
			for (i in cache.indices step 2) {
				if (cache[i] === sampler) {
					return cache[i + 1] as SiteField?
				}
			}
			var found: SiteField? = null
			sampler.depth().mapAll { f ->
				if (f is SiteDensity && f.noise.noise() != null && found == null) {
					found = f.siteField()
				}
				f
			}
			synchronized(SiteField::class.java) {
				val next = arrayOfNulls<Any>(min(samplers.size, 14) + 2)
				System.arraycopy(samplers, 0, next, 2, next.size - 2)
				next[0] = sampler
				next[1] = found
				samplers = next
			}
			return found
		}

		private fun overlaps(a: Site, b: Site): Boolean =
			hypot((a.x - b.x).toDouble(), (a.z - b.z).toDouble()) < a.core * 1.15 + b.core * 1.15 + 0.5 * (a.ramp + b.ramp)

		private fun cellKey(gx: Int, gz: Int): Long = (gx.toLong() shl 32) xor (gz.toLong() and 0xFFFFFFFFL)

		internal fun mix(z0: Long): Long {
			var z = z0
			z = (z xor (z ushr 30)) * MIX1
			z = (z xor (z ushr 27)) * MIX2
			return z xor (z ushr 31)
		}

		internal fun unit(h: Long): Double = (h ushr 11) * UNIT
	}
}
