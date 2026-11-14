package io.github.menonng.biomesmod.terrain.landform

import io.github.menonng.biomesmod.BiomesMod
import io.github.menonng.biomesmod.site.SiteField
import io.github.menonng.biomesmod.terrain.util.WorldSampler
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `biomesmod:landform`: grid-placed standing landforms. Placed once per chunk (placement `[]`); every chunk finds the
 * instances whose footprint overlaps it and writes only its own columns.
 */
class LandformFeature : Feature<LandformConfig>(LandformConfig.CODEC) {
	override fun place(ctx: FeaturePlaceContext<LandformConfig>): Boolean {
		val t0 = System.nanoTime()
		val cfg = ctx.config()
		val o = ctx.origin()
		val x0 = o.x and 15.inv()
		val z0 = o.z and 15.inv()
		val sampler = WorldSampler.of(ctx)
		val list = instancesOverlapping(sampler, cfg, x0, z0, x0 + 15, z0 + 15)
		var writes = 0
		if (list.isNotEmpty()) {
			val w = ChunkWriter(ctx.level(), ctx.chunkGenerator(), x0, z0, sampler.seaLevel)
			for (lf in list) {
				lf.write(w)
			}
			writes = w.writes
		}
		record(cfg.shape.name(), System.nanoTime() - t0, list.isNotEmpty())
		return writes > 0
	}

	private class Stats {
		val chunks = LongAdder()
		val active = LongAdder()
		val nanos = LongAdder()
		val activeNanos = LongAdder()
		val max = AtomicLong()
	}

	companion object {
		private val NONE = Any()
		private const val CACHE_LIMIT = 50_000
		private const val TRIES = 6
		private val GOLDEN = 0x9E3779B97F4A7C15uL.toLong()
		private val STATS = ConcurrentHashMap<String, Stats>()

		/** Instances of [cfg] whose footprint overlaps the block box [x0..x1] x [z0..z1]. */
		fun instancesOverlapping(sampler: WorldSampler, cfg: LandformConfig, x0: Int, z0: Int, x1: Int, z1: Int): List<Landform> {
			if (cfg.minDistance < 0) {
				return siteInstances(sampler, cfg, x0, z0, x1, z1)
			}
			val reach = cfg.shape.maxReach()
			val sp = cfg.spacing
			val out = ArrayList<Landform>(2)
			for (gz in Math.floorDiv(z0 - reach, sp)..Math.floorDiv(z1 + reach, sp)) {
				for (gx in Math.floorDiv(x0 - reach, sp)..Math.floorDiv(x1 + reach, sp)) {
					val lf = instance(sampler, cfg, gx, gz)
					if (lf != null && lf.overlaps(x0, z0, x1, z1)) {
						out.add(lf)
					}
				}
			}
			return out
		}

		private fun cached(map: ConcurrentHashMap<LandformConfig.CellKey, Any>, key: LandformConfig.CellKey, compute: () -> Landform?): Landform? {
			var v = map[key]
			if (v == null) {
				val fresh: Any = compute() ?: NONE
				if (map.size > CACHE_LIMIT) {
					map.clear()
				}
				v = map.putIfAbsent(key, fresh) ?: fresh
			}
			return if (v === NONE) null else v as Landform
		}

		private fun key(sampler: WorldSampler, seed: Long, x: Int, z: Int) = LandformConfig.CellKey(sampler.level.level, seed, x, z)

		/** The (cached) instance of grid cell (gx, gz), or null. Pure function of world seed, config and cell. */
		internal fun instance(sampler: WorldSampler, cfg: LandformConfig, gx: Int, gz: Int): Landform? =
			cached(cfg.cache, key(sampler, sampler.seed, gx, gz)) { resolve(sampler, cfg, gx, gz) }

		/** Cached candidate of cell (gx, gz) before the separation rule (all other checks passed), or null. */
		internal fun rawInstance(sampler: WorldSampler, cfg: LandformConfig, gx: Int, gz: Int): Landform? =
			cached(cfg.rawCache, key(sampler, sampler.seed, gx, gz)) { resolveRaw(sampler, cfg, gx, gz) }

		private fun central(cfg: LandformConfig, lf: Landform, sx: Int, sz: Int): Boolean {
			val m = cfg.minDistance
			val fx = (lf.cx - sx.toDouble() * m) / m
			val fz = (lf.cz - sz.toDouble() * m) / m
			return fx in 0.15..0.85 && fz in 0.15..0.85
		}

		/** The best candidate of super-cell (sx, sz), or null. Cached; pure function of seed, config and super-cell. */
		private fun superWinner(sampler: WorldSampler, cfg: LandformConfig, sx: Int, sz: Int): Landform? =
			cached(cfg.superCache, key(sampler, sampler.seed xor 0x5C, sx, sz)) {
				val m = cfg.minDistance
				val sp = cfg.spacing
				var best: Landform? = null
				var bestPr = Long.MIN_VALUE
				for (gz in Math.floorDiv(sz * m - sp, sp)..Math.floorDiv((sz + 1) * m + sp, sp)) {
					for (gx in Math.floorDiv(sx * m - sp, sp)..Math.floorDiv((sx + 1) * m + sp, sp)) {
						val c = rawInstance(sampler, cfg, gx, gz) ?: continue
						if (Math.floorDiv(c.cx, m) != sx || Math.floorDiv(c.cz, m) != sz || !central(cfg, c, sx, sz)) {
							continue
						}
						val pr = priority(sampler, cfg, gx, gz)
						if (best == null || pr > bestPr) {
							best = c
							bestPr = pr
						}
					}
				}
				best
			}

		private fun priority(sampler: WorldSampler, cfg: LandformConfig, gx: Int, gz: Int): Long =
			LNoise.hash(sampler.seed xor LNoise.mix(cfg.salt * 0x632BE59BD9B4E019L + 0x5EED), gx, gz)

		/**
		 * Separation rule on a dense grid: a valid candidate survives unless a neighbouring valid candidate with a higher
		 * (hash) priority stands closer than 3/4 of their combined reach. Only one level deep, so it is a pure function
		 * of seed and cells: neighbouring chunks always agree, and two accepted landforms never pile onto each other.
		 */
		private fun resolve(sampler: WorldSampler, cfg: LandformConfig, gx: Int, gz: Int): Landform? {
			val lf = rawInstance(sampler, cfg, gx, gz) ?: return null
			if (cfg.minDistance > 0) {
				// Sparse landmarks: the world is cut into super-cells of min_distance; each keeps only its best candidate
				// (highest priority) whose centre lies in the cell's central 70%, so two winners are at least 0.3 *
				// min_distance apart and, unlike a pairwise rule, a chain of rejections can never empty a region.
				val m = cfg.minDistance
				return if (superWinner(sampler, cfg, Math.floorDiv(lf.cx, m), Math.floorDiv(lf.cz, m)) === lf) lf else null
			}
			val pr = priority(sampler, cfg, gx, gz)
			val k = max(1, ceil(max(1.5 * cfg.shape.maxReach(), cfg.minDistance.toDouble()) / cfg.spacing).toInt())
			for (dz in -k..k) {
				for (dx in -k..k) {
					if (dx == 0 && dz == 0) {
						continue
					}
					val o = rawInstance(sampler, cfg, gx + dx, gz + dz) ?: continue
					val d = hypot((o.cx - lf.cx).toDouble(), (o.cz - lf.cz).toDouble())
					if (d < max((lf.reach + o.reach) * 0.75, cfg.minDistance.toDouble()) && priority(sampler, cfg, gx + dx, gz + dz) > pr) {
						return null
					}
				}
			}
			return lf
		}

		private fun cellSeed(sampler: WorldSampler, cfg: LandformConfig, gx: Int, gz: Int): Long =
			LNoise.hash(sampler.seed xor LNoise.mix(cfg.salt * GOLDEN + cfg.shape.name().hashCode()), gx, gz)

		private fun resolveRaw(sampler: WorldSampler, cfg: LandformConfig, gx: Int, gz: Int): Landform? {
			val cellSeed = cellSeed(sampler, cfg, gx, gz)
			val r = LNoise.Rng(cellSeed)
			if (!r.chance(cfg.chance.toDouble())) {
				return null
			}
			// Mod biome patches are often small and irregular, so a cell tries a few positions (deterministic from the cell
			// seed) and keeps the first whose site and footprint fit, instead of giving up after one.
			for (attempt in 0 until TRIES) {
				val lf = tryAt(sampler, cfg, cellSeed + attempt * GOLDEN, r, gx, gz)
				if (lf != null) {
					return lf
				}
			}
			return null
		}

		// ---- one per site (min_distance -1): unique landmarks stand near the centre of their biome's site

		private fun siteField(sampler: WorldSampler): SiteField? =
			SiteField.forSampler(sampler.level.level.chunkSource.randomState().sampler())

		private fun siteInstances(sampler: WorldSampler, cfg: LandformConfig, x0: Int, z0: Int, x1: Int, z1: Int): List<Landform> {
			val field = siteField(sampler) ?: return listOf()
			val reach = cfg.shape.maxReach()
			val cx = (x0 + x1) / 2
			val cz = (z0 + z1) / 2
			val half = max(x1 - x0, z1 - z0) * 0.75
			val out = ArrayList<Landform>(1)
			// Search far enough for the largest sites of a preset with stretched climate (see SiteConfig.scaled).
			val search = (half + reach + 0.4 * field.config.specs.maxOfOrNull { it.coreMax } .let { (it ?: 800.0) * 2 }).toInt()
			for (s in field.sitesNear(cx, cz, search, null)) {
				if (hypot((s.x - cx).toDouble(), (s.z - cz).toDouble()) > half + reach + 0.36 * s.core) {
					continue
				}
				val lf = siteInstance(sampler, cfg, s)
				if (lf != null && lf.overlaps(x0, z0, x1, z1)) {
					out.add(lf)
				}
			}
			return out
		}

		/** The landform of one site (cached), or null when the site is another biome's or nothing fits. */
		internal fun siteInstance(sampler: WorldSampler, cfg: LandformConfig, s: SiteField.Site): Landform? =
			cached(cfg.cache, key(sampler, sampler.seed xor 0x5173_5173_5173L, s.x, s.z)) { resolveSite(sampler, cfg, s) }

		private fun resolveSite(sampler: WorldSampler, cfg: LandformConfig, s: SiteField.Site): Landform? {
			val sea = sampler.seaLevel
			val centreFloor = sampler.baseFloorHeight(s.x, s.z) - 1
			if (!cfg.biomes.everywhere() && !cfg.biomes.test(sampler.biomeAt(s.x, max(centreFloor, sea), s.z))) {
				return null
			}
			val seed = LNoise.mix(s.seed xor (cfg.salt * GOLDEN + cfg.shape.name().hashCode()))
			val r = LNoise.Rng(seed)
			// The landmark stands in the middle of some sites and somewhere off to a side in the others.
			val central = r.next() < 0.4
			for (attempt in 0 until 12) {
				val a = r.angle()
				val d = if (central) (if (attempt == 0) 0.0 else s.core * 0.3 * sqrt(r.next()))
					else if (attempt >= 9) 0.0 else kotlin.math.min(s.core * (0.25 + 0.4 * sqrt(r.next())) * (1 - attempt / 14.0),
						max(0.0, s.core * 1.05 - cfg.shape.maxReach() * 0.85))
				val x = LNoise.floor(s.x + cos(a) * d)
				val z = LNoise.floor(s.z + sin(a) * d)
				val floor0 = sampler.baseFloorHeight(x, z) - 1
				val range = cfg.baseYRange()
				if (floor0 - sea < range.min || floor0 - sea > range.max) {
					continue
				}
				if (!cfg.biomes.everywhere() && !cfg.biomes.test(sampler.biomeAt(x, max(floor0, sea), z))) {
					continue
				}
				val lf = cfg.shape.create(Site(sampler, cfg, seed + attempt * GOLDEN, x, z, floor0))
				if (lf != null) {
					return lf
				}
			}
			return null
		}

		private fun tryAt(sampler: WorldSampler, cfg: LandformConfig, siteSeed: Long, r: LNoise.Rng, gx: Int, gz: Int): Landform? {
			val sp = cfg.spacing
			val x = LNoise.floor((gx + 0.5 + (r.next() - 0.5) * cfg.jitter) * sp)
			val z = LNoise.floor((gz + 0.5 + (r.next() - 0.5) * cfg.jitter) * sp)
			val sea = sampler.seaLevel
			// Cheap pre-check (biome near the surface) before sampling the noise terrain height, which costs far more. The
			// third probe is above almost any surface, so it also finds biomes on high site plateaus.
			if (!cfg.biomes.everywhere() && !cfg.biomes.test(sampler.biomeAt(x, sea, z)) && !cfg.biomes.test(sampler.biomeAt(x, sea + 48, z)) &&
				!cfg.biomes.test(sampler.biomeAt(x, sea + 180, z))) {
				return null
			}
			val floor0 = sampler.baseFloorHeight(x, z) - 1
			val range = cfg.baseYRange()
			if (floor0 - sea < range.min || floor0 - sea > range.max) {
				return null
			}
			val biomeY = max(floor0, sea)
			if (!cfg.biomes.everywhere() && !cfg.biomes.test(sampler.biomeAt(x, biomeY, z))) {
				return null
			}
			val lf = cfg.shape.create(Site(sampler, cfg, siteSeed, x, z, floor0)) ?: return null
			if (cfg.requireInside && !cfg.biomes.everywhere()) {
				for (ring in 0 until 2) {
					// Outer ring at 0.65 of the reach: the rest is low skirt/talus that may run a little past the edge.
					val rad = lf.reach * (if (ring == 0) 0.65 else 0.4)
					val n = if (ring == 0) 16 else 8
					for (i in 0 until n) {
						val a = Math.PI * 2 * (i + 0.5 * ring) / n
						val sx = LNoise.floor(x + cos(a) * rad)
						val sz = LNoise.floor(z + sin(a) * rad)
						if (!cfg.biomes.test(sampler.biomeAt(sx, biomeY, sz))) {
							return null
						}
					}
				}
			}
			return lf
		}

		/**
		 * Diagnostic: replays the placement decision for every grid cell whose centre lies within [radius] of (cx, cz)
		 * and counts where candidates drop out (mirrors [resolve] step by step), adding each placed instance centre to
		 * [placed] when given.
		 */
		fun diagnose(sampler: WorldSampler, cfg: LandformConfig, cx: Int, cz: Int, radius: Int, placed: MutableList<IntArray>? = null): String {
			if (cfg.minDistance < 0) {
				val field = siteField(sampler)
				var sites = 0
				var ok = 0
				if (field != null) {
					for (s in field.sitesNear(cx, cz, radius, null)) {
						sites++
						val lf = siteInstance(sampler, cfg, s) ?: continue
						ok++
						placed?.add(intArrayOf(lf.cx, lf.cz))
					}
				}
				return "%s: one per site, %d site(s) within %d -> %d placed".format(cfg.shape.name(), sites, radius, ok)
			}
			val sp = cfg.spacing
			var cells = 0
			var chance = 0
			var range = 0
			var biome = 0
			var shape = 0
			var inside = 0
			var sep = 0
			var ok = 0
			for (gz in Math.floorDiv(cz - radius, sp)..Math.floorDiv(cz + radius, sp)) {
				for (gx in Math.floorDiv(cx - radius, sp)..Math.floorDiv(cx + radius, sp)) {
					cells++
					val cellSeed = cellSeed(sampler, cfg, gx, gz)
					val r = LNoise.Rng(cellSeed)
					if (!r.chance(cfg.chance.toDouble())) {
						chance++
						continue
					}
					val x = LNoise.floor((gx + 0.5 + (r.next() - 0.5) * cfg.jitter) * sp)
					val z = LNoise.floor((gz + 0.5 + (r.next() - 0.5) * cfg.jitter) * sp)
					val floor0 = sampler.baseFloorHeight(x, z) - 1
					val sea = sampler.seaLevel
					val rg = cfg.baseYRange()
					if (floor0 - sea < rg.min || floor0 - sea > rg.max) {
						range++
						continue
					}
					val biomeY = max(floor0, sea)
					if (!cfg.biomes.everywhere() && !cfg.biomes.test(sampler.biomeAt(x, biomeY, z))) {
						biome++
						continue
					}
					if (rawInstance(sampler, cfg, gx, gz) == null) {
						if (cfg.shape.create(Site(sampler, cfg, cellSeed, x, z, floor0)) == null) {
							shape++
						} else {
							inside++
						}
					} else {
						val lf = instance(sampler, cfg, gx, gz)
						if (lf == null) {
							sep++
						} else {
							ok++
							placed?.add(intArrayOf(lf.cx, lf.cz))
						}
					}
				}
			}
			return "%s: %d cells, rejected chance %d, base-y %d, biome %d, shape %d, footprint %d, separation %d -> %d placed".format(
				cfg.shape.name(), cells, chance, range, biome, shape, inside, sep, ok)
		}

		// ---- timing ----

		private fun record(shape: String, nanos: Long, active: Boolean) {
			val s = STATS.computeIfAbsent(shape) { Stats() }
			s.chunks.increment()
			s.nanos.add(nanos)
			if (active) {
				s.active.increment()
				s.activeNanos.add(nanos)
			}
			s.max.accumulateAndGet(nanos) { a, b -> max(a, b) }
			if (s.chunks.sum() % 4096 == 0L) {
				BiomesMod.LOGGER.info("[landform] {}", summary(shape))
			}
		}

		/** Timing summary for one shape: all chunks, chunks containing a landform, and the slowest chunk. */
		fun summary(shape: String): String {
			val s = STATS[shape] ?: return "$shape: no chunks"
			val n = max(1L, s.chunks.sum())
			val a = max(1L, s.active.sum())
			return "%s: %d chunks avg %.3f ms; %d with landform avg %.3f ms; max %.2f ms".format(shape, s.chunks.sum(),
				s.nanos.sum() / 1e6 / n, s.active.sum(), s.activeNanos.sum() / 1e6 / a, s.max.get() / 1e6)
		}

		fun resetStats() {
			STATS.clear()
		}
	}
}
