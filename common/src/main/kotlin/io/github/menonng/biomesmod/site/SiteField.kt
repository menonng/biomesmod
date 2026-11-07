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
			val target = spec.targetY + spec.relief * (s0 - reference)
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
		if (delta == 0.0 && detail == 1.0) {
			return Column.NONE
		}
		return Column(true, s0, delta, max(FLAT_DETAIL, detail), flat)
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
