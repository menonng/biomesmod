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
		val candidates = config.specs.filter { it.accepts(t, hu, co, er) }
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
		val b = blend(x, z)
		val a = b.a ?: return Column.NONE
		val s0 = originalSurface(x, z, depth)
		var delta = 0.0
		var detail = 1.0
		var flat = 0.0
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
			// Ridged peaks (mountain ranges in the manner of Tectonic): sharp crests and arêtes over the target height.
			// The ranges rise only inside the site (smoothstep of the weight), so the edges stay gentle and blend out.
			val peaks = if (spec.ridges > 0) spec.ridges * ridges(x.toDouble(), z.toDouble()) * smooth01((w - 0.25) / 0.6) else 0.0
			// Soft ceiling: heights approach the configured maximum smoothly instead of being cut flat at the build limit.
			val target = softCap(spec.targetY + spec.relief * (s0 - reference) + peaks)
			val d = if (spec.terrain == SiteConfig.Terrain.MOUNTAIN) max(0.0, target - s0) else target - s0
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
		// Fjords: channels cut from the site centre towards the sea, below sea level (the aquifer floods them).
		for ((site, w) in arrayOf(a to b.wa, b.b to b.wb)) {
			val ch = site?.spec?.channels ?: continue
			if (w <= 0) {
				continue
			}
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

	/** Ridged multifractal in [0, 1]: thin sharp crests joined into ranges, broad lows between them. */
	internal fun ridges(x: Double, z: Double): Double {
		// Domain warp so the ranges bend and branch instead of running straight.
		val wx = x + 140 * noise.getValue(x * 0.7 + 1733.0, 50.0, z * 0.7)
		val wz = z + 140 * noise.getValue(x * 0.7, 50.0, z * 0.7 - 2213.0)
		var sum = 0.0
		var amp = 1.0
		var weight = 1.0
		var f = 1.2
		var norm = 0.0
		for (o in 0 until 4) {
			val n = noise.getValue(wx * f + o * 1013.7, 120.0 + o * 53, wz * f - o * 419.3)
			var r = 1.0 - kotlin.math.abs(n) * 1.7
			r = (r.coerceIn(0.0, 1.0)).let { it * it }
			r *= weight
			weight = (r * 1.6).coerceIn(0.0, 1.0)
			sum += r * amp
			norm += amp
			amp *= 0.5
			f *= 2.0
		}
		return (sum / norm * 1.35).coerceIn(0.0, 1.0)
	}

	private class ChannelInfo(val angle: Double, val phase: Double, val phase2: Double)

	private val channelInfos = ConcurrentHashMap<Long, ChannelInfo>()

	/** Direction from the site centre to its lowest original ground (the sea), computed once per site. */
	private fun channelInfo(site: Site, depth: DensityFunction): ChannelInfo = channelInfos.computeIfAbsent(cellKey(site.x, site.z)) {
		var best = Double.MAX_VALUE
		var angle = 0.0
		val r = site.outer() * 0.92
		for (k in 0 until 24) {
			val a = k * PI / 12
			val h = originalSurface((site.x + kotlin.math.cos(a) * r).toInt(), (site.z + sin(a) * r).toInt(), depth)
			if (h < best) {
				best = h
				angle = a
			}
		}
		ChannelInfo(angle, unit(mix(site.seed + 71)) * PI * 2, unit(mix(site.seed + 73)) * PI * 2)
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
		for (k in 0 until ch.count) {
			// The main fjord heads inland from the sea; side fjords branch off it at a third of the way.
			val turn = if (k == 0) 0.0 else (if (k % 2 == 1) 0.75 else -0.85) * (1 + 0.2 * unit(mix(site.seed + 80 + k)))
			val ox = if (k == 0) site.x.toDouble() else site.x + kotlin.math.cos(info.angle) * outer * 0.3
			val oz = if (k == 0) site.z.toDouble() else site.z + sin(info.angle) * outer * 0.3
			val dir = info.angle + turn
			val cx = kotlin.math.cos(dir)
			val cz = sin(dir)
			val px = x - ox
			val pz = z - oz
			val along = px * cx + pz * cz
			val head = if (k == 0) -0.25 * site.core else 0.0
			val end = if (k == 0) outer * 1.05 else outer * 0.55
			if (along < head - ch.width * 2 || along > end) {
				continue
			}
			val meander = ch.width * 1.3 * sin(along / 170.0 + info.phase + k) + ch.width * 0.5 * sin(along / 61.0 + info.phase2 + 2 * k)
			val across = -px * cz + pz * cx - meander
			val t = ((along - head) / (end - head)).coerceIn(0.0, 1.0)
			val half = ch.width * (if (k == 0) 0.6 + 0.6 * t else 0.5 + 0.3 * t)
			val q = kotlin.math.abs(across) / half
			if (q >= 1.0) {
				continue
			}
			// Fjord head: the floor climbs to the surrounding ground over the first stretch.
			val headRise = ((along - head) / (ch.width * 3)).coerceIn(0.0, 1.0)
			val floor = SEA_LEVEL - ch.depth * headRise + (surface - SEA_LEVEL) * (1 - headRise) * 0.85
			val profile = q * q * q * (0.35 + 0.65 * q)
			best = kotlin.math.min(best, floor + (surface - floor) * profile)
		}
		return best
	}

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

		/** Sea level of the overworld (fjord floors are cut below it). */
		private const val SEA_LEVEL = 63.0

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
