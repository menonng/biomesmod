package io.github.menonng.biomesmod.terrain.landform;

import io.github.menonng.biomesmod.BiomesMod;
import io.github.menonng.biomesmod.terrain.util.WorldSampler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

/**
 * {@code biomesmod:landform}: grid-placed standing landforms. Placed once per chunk (placement {@code []});
 * every chunk finds the instances whose footprint overlaps it and writes only its own columns.
 */
public final class LandformFeature extends Feature<LandformConfig> {
	private static final Object NONE = new Object();
	private static final int CACHE_LIMIT = 50_000;

	public LandformFeature() {
		super(LandformConfig.CODEC);
	}

	@Override
	public boolean place(FeaturePlaceContext<LandformConfig> ctx) {
		long t0 = System.nanoTime();
		LandformConfig cfg = ctx.config();
		BlockPos o = ctx.origin();
		int x0 = o.getX() & ~15;
		int z0 = o.getZ() & ~15;
		WorldSampler sampler = WorldSampler.of(ctx);
		List<Landform> list = instancesOverlapping(sampler, cfg, x0, z0, x0 + 15, z0 + 15);
		int writes = 0;
		if (!list.isEmpty()) {
			ChunkWriter w = new ChunkWriter(ctx.level(), ctx.chunkGenerator(), x0, z0, sampler.seaLevel());
			for (Landform lf : list) {
				lf.write(w);
			}
			writes = w.writes;
		}
		record(cfg.shape().name(), System.nanoTime() - t0, !list.isEmpty());
		return writes > 0;
	}

	/** Instances of {@code cfg} whose footprint overlaps the block box [x0..x1] x [z0..z1]. */
	public static List<Landform> instancesOverlapping(WorldSampler sampler, LandformConfig cfg, int x0, int z0, int x1, int z1) {
		if (cfg.minDistance < 0) {
			return siteInstances(sampler, cfg, x0, z0, x1, z1);
		}
		int reach = cfg.shape().maxReach();
		int sp = cfg.spacing;
		List<Landform> out = new ArrayList<>(2);
		for (int gz = Math.floorDiv(z0 - reach, sp); gz <= Math.floorDiv(z1 + reach, sp); gz++) {
			for (int gx = Math.floorDiv(x0 - reach, sp); gx <= Math.floorDiv(x1 + reach, sp); gx++) {
				Landform lf = instance(sampler, cfg, gx, gz);
				if (lf != null && lf.overlaps(x0, z0, x1, z1)) {
					out.add(lf);
				}
			}
		}
		return out;
	}

	/** The (cached) instance of grid cell (gx, gz), or null. Pure function of world seed, config and cell. */
	static Landform instance(WorldSampler sampler, LandformConfig cfg, int gx, int gz) {
		LandformConfig.CellKey key = new LandformConfig.CellKey(sampler.level().getLevel(),
				sampler.seed(), gx, gz);
		Object v = cfg.cache.get(key);
		if (v == null) {
			v = resolve(sampler, cfg, gx, gz);
			if (v == null) {
				v = NONE;
			}
			if (cfg.cache.size() > CACHE_LIMIT) {
				cfg.cache.clear();
			}
			Object prev = cfg.cache.putIfAbsent(key, v);
			if (prev != null) {
				v = prev;
			}
		}
		return v == NONE ? null : (Landform) v;
	}

	/** Cached candidate of cell (gx, gz) before the separation rule (all other checks passed), or null. */
	static Landform rawInstance(WorldSampler sampler, LandformConfig cfg, int gx, int gz) {
		LandformConfig.CellKey key = new LandformConfig.CellKey(sampler.level().getLevel(), sampler.seed(), gx, gz);
		Object v = cfg.rawCache.get(key);
		if (v == null) {
			v = resolveRaw(sampler, cfg, gx, gz);
			if (v == null) {
				v = NONE;
			}
			if (cfg.rawCache.size() > CACHE_LIMIT) {
				cfg.rawCache.clear();
			}
			Object prev = cfg.rawCache.putIfAbsent(key, v);
			if (prev != null) {
				v = prev;
			}
		}
		return v == NONE ? null : (Landform) v;
	}


	private static boolean central(LandformConfig cfg, Landform lf, int sx, int sz) {
		int m = cfg.minDistance;
		double fx = (lf.cx - (double) sx * m) / m;
		double fz = (lf.cz - (double) sz * m) / m;
		return fx >= 0.15 && fx <= 0.85 && fz >= 0.15 && fz <= 0.85;
	}

	/** The best candidate of super-cell (sx, sz), or null. Cached; pure function of seed, config and super-cell. */
	private static Landform superWinner(WorldSampler sampler, LandformConfig cfg, int sx, int sz) {
		LandformConfig.CellKey key = new LandformConfig.CellKey(sampler.level().getLevel(), sampler.seed() ^ 0x5C, sx, sz);
		Object v = cfg.superCache.get(key);
		if (v == null) {
			int m = cfg.minDistance;
			int sp = cfg.spacing;
			Landform best = null;
			long bestPr = Long.MIN_VALUE;
			for (int gz = Math.floorDiv(sz * m - sp, sp); gz <= Math.floorDiv((sz + 1) * m + sp, sp); gz++) {
				for (int gx = Math.floorDiv(sx * m - sp, sp); gx <= Math.floorDiv((sx + 1) * m + sp, sp); gx++) {
					Landform c = rawInstance(sampler, cfg, gx, gz);
					if (c == null || Math.floorDiv(c.cx, m) != sx || Math.floorDiv(c.cz, m) != sz || !central(cfg, c, sx, sz)) {
						continue;
					}
					long pr = priority(sampler, cfg, gx, gz);
					if (best == null || pr > bestPr) {
						best = c;
						bestPr = pr;
					}
				}
			}
			v = best == null ? NONE : best;
			if (cfg.superCache.size() > CACHE_LIMIT) {
				cfg.superCache.clear();
			}
			Object prev = cfg.superCache.putIfAbsent(key, v);
			if (prev != null) {
				v = prev;
			}
		}
		return v == NONE ? null : (Landform) v;
	}

	private static long priority(WorldSampler sampler, LandformConfig cfg, int gx, int gz) {
		return LNoise.hash(sampler.seed() ^ LNoise.mix(cfg.salt * 0x632BE59BD9B4E019L + 0x5EED), gx, gz);
	}

	/**
	 * Separation rule on a dense grid: a valid candidate survives unless a neighbouring valid candidate with a higher
	 * (hash) priority stands closer than 3/4 of their combined reach. Only one level deep, so it is a pure function of
	 * seed and cells: neighbouring chunks always agree, and two accepted landforms never pile onto each other.
	 */
	private static Landform resolve(WorldSampler sampler, LandformConfig cfg, int gx, int gz) {
		Landform lf = rawInstance(sampler, cfg, gx, gz);
		if (lf == null) {
			return null;
		}
		if (cfg.minDistance > 0) {
			// Sparse landmarks: the world is cut into super-cells of min_distance; each keeps only its best candidate
			// (highest priority) whose centre lies in the cell's central 70%, so two winners are at least
			// 0.3 * min_distance apart and, unlike a pairwise rule, a chain of rejections can never empty a region.
			int m = cfg.minDistance;
			int sx = Math.floorDiv(lf.cx, m);
			int sz = Math.floorDiv(lf.cz, m);
			return superWinner(sampler, cfg, sx, sz) == (Object) lf ? lf : null;
		}
		long pr = priority(sampler, cfg, gx, gz);
		int k = Math.max(1, (int) Math.ceil(Math.max(1.5 * cfg.shape().maxReach(), cfg.minDistance) / cfg.spacing));
		for (int dz = -k; dz <= k; dz++) {
			for (int dx = -k; dx <= k; dx++) {
				if (dx == 0 && dz == 0) {
					continue;
				}
				Landform o = rawInstance(sampler, cfg, gx + dx, gz + dz);
				if (o == null) {
					continue;
				}
				double d = Math.hypot(o.cx - lf.cx, o.cz - lf.cz);
				if (d < Math.max((lf.reach + o.reach) * 0.75, cfg.minDistance) && priority(sampler, cfg, gx + dx, gz + dz) > pr) {
					return null;
				}
			}
		}
		return lf;
	}

	private static Landform resolveRaw(WorldSampler sampler, LandformConfig cfg, int gx, int gz) {
		long cellSeed = LNoise.hash(sampler.seed() ^ LNoise.mix(cfg.salt * 0x9E3779B97F4A7C15L + cfg.shape().name().hashCode()), gx, gz);
		LNoise.Rng r = new LNoise.Rng(cellSeed);
		if (!r.chance(cfg.chance)) {
			return null;
		}
		// Mod biome patches are often small and irregular, so a cell tries a few positions (deterministic from the
		// cell seed) and keeps the first whose site and footprint fit, instead of giving up after one.
		for (int attempt = 0; attempt < TRIES; attempt++) {
			Landform lf = tryAt(sampler, cfg, cellSeed + attempt * 0x9E3779B97F4A7C15L, r, gx, gz);
			if (lf != null) {
				return lf;
			}
		}
		return null;
	}

	private static final int TRIES = 6;

	// ---- one per site (min_distance -1): unique landmarks stand near the centre of their biome's site

	private static io.github.menonng.biomesmod.site.SiteField siteField(WorldSampler sampler) {
		return io.github.menonng.biomesmod.site.SiteField.forSampler(sampler.level().getLevel().getChunkSource().randomState().sampler());
	}

	private static List<Landform> siteInstances(WorldSampler sampler, LandformConfig cfg, int x0, int z0, int x1, int z1) {
		io.github.menonng.biomesmod.site.SiteField field = siteField(sampler);
		if (field == null) {
			return List.of();
		}
		int reach = cfg.shape().maxReach();
		int cx = (x0 + x1) / 2;
		int cz = (z0 + z1) / 2;
		double half = Math.max(x1 - x0, z1 - z0) * 0.75;
		List<Landform> out = new ArrayList<>(1);
		for (io.github.menonng.biomesmod.site.SiteField.Site s : field.sitesNear(cx, cz, (int) (half + reach + 0.4 * 1600), null)) {
			if (Math.hypot(s.x() - cx, s.z() - cz) > half + reach + 0.36 * s.core()) {
				continue;
			}
			Landform lf = siteInstance(sampler, cfg, s);
			if (lf != null && lf.overlaps(x0, z0, x1, z1)) {
				out.add(lf);
			}
		}
		return out;
	}

	/** The landform of one site (cached), or null when the site is another biome's or nothing fits. */
	static Landform siteInstance(WorldSampler sampler, LandformConfig cfg, io.github.menonng.biomesmod.site.SiteField.Site s) {
		LandformConfig.CellKey key = new LandformConfig.CellKey(sampler.level().getLevel(), sampler.seed() ^ 0x5173_5173_5173L, s.x(), s.z());
		Object v = cfg.cache.get(key);
		if (v == null) {
			v = resolveSite(sampler, cfg, s);
			if (v == null) {
				v = NONE;
			}
			if (cfg.cache.size() > CACHE_LIMIT) {
				cfg.cache.clear();
			}
			Object prev = cfg.cache.putIfAbsent(key, v);
			if (prev != null) {
				v = prev;
			}
		}
		return v == NONE ? null : (Landform) v;
	}

	private static Landform resolveSite(WorldSampler sampler, LandformConfig cfg, io.github.menonng.biomesmod.site.SiteField.Site s) {
		int sea = sampler.seaLevel();
		int centreFloor = sampler.baseFloorHeight(s.x(), s.z()) - 1;
		if (!cfg.biomes.everywhere() && !cfg.biomes.test(sampler.biomeAt(s.x(), Math.max(centreFloor, sea), s.z()))) {
			return null;
		}
		long seed = LNoise.mix(s.seed() ^ (cfg.salt * 0x9E3779B97F4A7C15L + cfg.shape().name().hashCode()));
		LNoise.Rng r = new LNoise.Rng(seed);
		for (int attempt = 0; attempt < 10; attempt++) {
			double a = r.angle();
			double d = attempt == 0 ? 0 : s.core() * 0.35 * Math.sqrt(r.next());
			int x = LNoise.floor(s.x() + Math.cos(a) * d);
			int z = LNoise.floor(s.z() + Math.sin(a) * d);
			int floor0 = sampler.baseFloorHeight(x, z) - 1;
			Span range = cfg.baseYRange();
			if (floor0 - sea < range.min() || floor0 - sea > range.max()) {
				continue;
			}
			if (!cfg.biomes.everywhere() && !cfg.biomes.test(sampler.biomeAt(x, Math.max(floor0, sea), z))) {
				continue;
			}
			Landform lf = cfg.shape().create(new Site(sampler, cfg, seed + attempt * 0x9E3779B97F4A7C15L, x, z, floor0));
			if (lf != null) {
				return lf;
			}
		}
		return null;
	}

	private static Landform tryAt(WorldSampler sampler, LandformConfig cfg, long siteSeed, LNoise.Rng r, int gx, int gz) {
		int sp = cfg.spacing;
		int x = LNoise.floor((gx + 0.5 + (r.next() - 0.5) * cfg.jitter) * sp);
		int z = LNoise.floor((gz + 0.5 + (r.next() - 0.5) * cfg.jitter) * sp);
		int sea = sampler.seaLevel();
		// Cheap pre-check (biome near the surface) before sampling the noise terrain height, which costs far more.
		// The third probe is above almost any surface, so it also finds biomes on high site plateaus.
		if (!cfg.biomes.everywhere() && !cfg.biomes.test(sampler.biomeAt(x, sea, z)) && !cfg.biomes.test(sampler.biomeAt(x, sea + 48, z))
				&& !cfg.biomes.test(sampler.biomeAt(x, sea + 180, z))) {
			return null;
		}
		int floor0 = sampler.baseFloorHeight(x, z) - 1;
		Span range = cfg.baseYRange();
		if (floor0 - sea < range.min() || floor0 - sea > range.max()) {
			return null;
		}
		int biomeY = Math.max(floor0, sea);
		if (!cfg.biomes.everywhere() && !cfg.biomes.test(sampler.biomeAt(x, biomeY, z))) {
			return null;
		}
		Site site = new Site(sampler, cfg, siteSeed, x, z, floor0);
		Landform lf = cfg.shape().create(site);
		if (lf == null) {
			return null;
		}
		if (cfg.requireInside && !cfg.biomes.everywhere()) {
			for (int ring = 0; ring < 2; ring++) {
				// Outer ring at 0.65 of the reach: the rest is low skirt/talus that may run a little past the edge.
				double rad = lf.reach * (ring == 0 ? 0.65 : 0.4);
				int n = ring == 0 ? 16 : 8;
				for (int i = 0; i < n; i++) {
					double a = Math.PI * 2 * (i + 0.5 * ring) / n;
					int sx = LNoise.floor(x + Math.cos(a) * rad);
					int sz = LNoise.floor(z + Math.sin(a) * rad);
					if (!cfg.biomes.test(sampler.biomeAt(sx, biomeY, sz))) {
						return null;
					}
				}
			}
		}
		return lf;
	}

	/**
	 * Diagnostic: replays the placement decision for every grid cell whose centre lies within {@code radius} of
	 * (cx, cz) and counts where candidates drop out. Mirrors {@link #resolve} step by step.
	 */
	public static String diagnose(WorldSampler sampler, LandformConfig cfg, int cx, int cz, int radius) {
		return diagnose(sampler, cfg, cx, cz, radius, null);
	}

	/** As {@link #diagnose(WorldSampler, LandformConfig, int, int, int)}, also adding each placed instance centre to {@code placed}. */
	public static String diagnose(WorldSampler sampler, LandformConfig cfg, int cx, int cz, int radius, List<int[]> placed) {
		if (cfg.minDistance < 0) {
			io.github.menonng.biomesmod.site.SiteField field = siteField(sampler);
			int sites = 0;
			int ok = 0;
			if (field != null) {
				for (io.github.menonng.biomesmod.site.SiteField.Site s : field.sitesNear(cx, cz, radius, null)) {
					sites++;
					Landform lf = siteInstance(sampler, cfg, s);
					if (lf != null) {
						ok++;
						if (placed != null) {
							placed.add(new int[] {lf.cx, lf.cz});
						}
					}
				}
			}
			return String.format("%s: one per site, %d site(s) within %d -> %d placed", cfg.shape().name(), sites, radius, ok);
		}
		int sp = cfg.spacing;
		int cells = 0, chance = 0, range = 0, biome = 0, shape = 0, inside = 0, sep = 0, ok = 0;
		for (int gz = Math.floorDiv(cz - radius, sp); gz <= Math.floorDiv(cz + radius, sp); gz++) {
			for (int gx = Math.floorDiv(cx - radius, sp); gx <= Math.floorDiv(cx + radius, sp); gx++) {
				cells++;
				long cellSeed = LNoise.hash(sampler.seed() ^ LNoise.mix(cfg.salt * 0x9E3779B97F4A7C15L + cfg.shape().name().hashCode()), gx, gz);
				LNoise.Rng r = new LNoise.Rng(cellSeed);
				if (!r.chance(cfg.chance)) {
					chance++;
					continue;
				}
				int x = LNoise.floor((gx + 0.5 + (r.next() - 0.5) * cfg.jitter) * sp);
				int z = LNoise.floor((gz + 0.5 + (r.next() - 0.5) * cfg.jitter) * sp);
				int floor0 = sampler.baseFloorHeight(x, z) - 1;
				int sea = sampler.seaLevel();
				Span rg = cfg.baseYRange();
				if (floor0 - sea < rg.min() || floor0 - sea > rg.max()) {
					range++;
					continue;
				}
				int biomeY = Math.max(floor0, sea);
				if (!cfg.biomes.everywhere() && !cfg.biomes.test(sampler.biomeAt(x, biomeY, z))) {
					biome++;
					continue;
				}
				if (rawInstance(sampler, cfg, gx, gz) == null) {
					if (cfg.shape().create(new Site(sampler, cfg, cellSeed, x, z, floor0)) == null) {
						shape++;
					} else {
						inside++;
					}
				} else if (instance(sampler, cfg, gx, gz) == null) {
					sep++;
				} else {
					ok++;
					if (placed != null) {
						Landform lf = instance(sampler, cfg, gx, gz);
						placed.add(new int[] {lf.cx, lf.cz});
					}
				}
			}
		}
		return String.format("%s: %d cells, rejected chance %d, base-y %d, biome %d, shape %d, footprint %d, separation %d -> %d placed",
				cfg.shape().name(), cells, chance, range, biome, shape, inside, sep, ok);
	}

	// ---- timing ----

	private static final class Stats {
		final LongAdder chunks = new LongAdder();
		final LongAdder active = new LongAdder();
		final LongAdder nanos = new LongAdder();
		final LongAdder activeNanos = new LongAdder();
		final AtomicLong max = new AtomicLong();
	}

	private static final Map<String, Stats> STATS = new ConcurrentHashMap<>();

	private static void record(String shape, long nanos, boolean active) {
		Stats s = STATS.computeIfAbsent(shape, k -> new Stats());
		s.chunks.increment();
		s.nanos.add(nanos);
		if (active) {
			s.active.increment();
			s.activeNanos.add(nanos);
		}
		s.max.accumulateAndGet(nanos, Math::max);
		if (s.chunks.sum() % 4096 == 0) {
			BiomesMod.LOGGER.info("[landform] {}", summary(shape));
		}
	}

	/** Timing summary for one shape: all chunks, chunks containing a landform, and the slowest chunk. */
	public static String summary(String shape) {
		Stats s = STATS.get(shape);
		if (s == null) {
			return shape + ": no chunks";
		}
		long n = Math.max(1, s.chunks.sum());
		long a = Math.max(1, s.active.sum());
		return String.format("%s: %d chunks avg %.3f ms; %d with landform avg %.3f ms; max %.2f ms", shape, s.chunks.sum(),
				s.nanos.sum() / 1e6 / n, s.active.sum(), s.activeNanos.sum() / 1e6 / a, s.max.get() / 1e6);
	}

	public static void resetStats() {
		STATS.clear();
	}
}
