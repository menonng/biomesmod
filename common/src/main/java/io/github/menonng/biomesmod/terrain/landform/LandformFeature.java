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

	private static Landform resolve(WorldSampler sampler, LandformConfig cfg, int gx, int gz) {
		long cellSeed = LNoise.hash(sampler.seed() ^ LNoise.mix(cfg.salt * 0x9E3779B97F4A7C15L + cfg.shape().name().hashCode()), gx, gz);
		LNoise.Rng r = new LNoise.Rng(cellSeed);
		if (!r.chance(cfg.chance)) {
			return null;
		}
		int sp = cfg.spacing;
		int x = LNoise.floor((gx + 0.5 + (r.next() - 0.5) * cfg.jitter) * sp);
		int z = LNoise.floor((gz + 0.5 + (r.next() - 0.5) * cfg.jitter) * sp);
		int floor0 = sampler.baseFloorHeight(x, z) - 1;
		int sea = sampler.seaLevel();
		Span range = cfg.baseYRange();
		if (floor0 - sea < range.min() || floor0 - sea > range.max()) {
			return null;
		}
		int biomeY = Math.max(floor0, sea);
		if (!cfg.biomes.everywhere() && !cfg.biomes.test(sampler.biomeAt(x, biomeY, z))) {
			return null;
		}
		Site site = new Site(sampler, cfg, cellSeed, x, z, floor0);
		Landform lf = cfg.shape().create(site);
		if (lf == null) {
			return null;
		}
		if (cfg.requireInside && !cfg.biomes.everywhere()) {
			for (int ring = 0; ring < 2; ring++) {
				double rad = lf.reach * (ring == 0 ? 0.95 : 0.55);
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
