package io.github.menonng.biomesmod.site;

import io.github.menonng.biomesmod.worldgen.Placements;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * The sites of one world: a pure function of the world seed and the vanilla climate.
 *
 * <p>The world is cut into square cells of {@code cell} blocks. Each cell may hold one site, centred at a jittered
 * point; the climate at that point (temperature, humidity, continentalness, erosion) picks a biome among the specs
 * that accept it. Sites never fight: when two would overlap, the one with the higher priority hash keeps its place
 * and the other is dropped (a site can only be dropped by a site that itself survives, so the result is the same
 * whatever order chunks generate in). The outline is a lobed circle, domain-warped by a seeded noise.
 *
 * <p>Used by {@link SiteDensity} (terrain) and {@link SiteBiomes} (biome source), which therefore always agree.
 */
public final class SiteField {
	/** One placed site. {@code core} is the full-strength radius before the lobes, {@code ramp} the fade width. */
	public record Site(int x, int z, double core, double ramp, SiteConfig.Spec spec, long seed, long priority) {
		public double outer() {
			return core * 1.3 + ramp;
		}

		/** Lobed outline: radius multiplier at angle {@code a}. */
		double shape(double a) {
			double p1 = (seed & 0xFFFF) / 65536.0 * Math.PI * 2;
			double p2 = ((seed >>> 16) & 0xFFFF) / 65536.0 * Math.PI * 2;
			double p3 = ((seed >>> 32) & 0xFFFF) / 65536.0 * Math.PI * 2;
			return 1 + 0.13 * Math.sin(2 * a + p1) + 0.08 * Math.sin(3 * a + p2) + 0.05 * Math.sin(5 * a + p3);
		}
	}

	/** Up to two contributing sites at a point and their weights (0..1, already normalised to sum at most 1). */
	public record Blend(Site a, double wa, Site b, double wb) {
		static final Blend NONE = new Blend(null, 0, null, 0);

		public double total() {
			return wa + wb;
		}
	}

	private static final Object NONE = new Object();
	private static final Map<NormalNoise, SiteField> BY_NOISE = Collections.synchronizedMap(new WeakHashMap<>());
	private static volatile Object[] samplers = new Object[0];

	private final SiteConfig config;
	private final long seed;
	private final NormalNoise noise;
	private final DensityFunction temperature;
	private final DensityFunction vegetation;
	private final DensityFunction continents;
	private final DensityFunction erosion;
	private final double reach;
	private final Map<Long, Object> raw = new ConcurrentHashMap<>();
	private final Map<Long, Object> resolved = new ConcurrentHashMap<>();

	private SiteField(SiteConfig config, NormalNoise noise, DensityFunction temperature, DensityFunction vegetation,
			DensityFunction continents, DensityFunction erosion) {
		this.config = config;
		this.noise = noise;
		this.temperature = temperature;
		this.vegetation = vegetation;
		this.continents = continents;
		this.erosion = erosion;
		this.seed = mix(Double.doubleToLongBits(noise.getValue(1234.567, 0.0, -2345.678)))
				^ mix(Double.doubleToLongBits(noise.getValue(-3456.5, 0.0, 4567.25)) + 0x5EEDL);
		this.reach = config.maxOuter();
	}

	/** The field for a wired site density function (one per world: all three share the same seeded noise). */
	static SiteField of(SiteDensity d) {
		NormalNoise n = d.noise().noise();
		synchronized (BY_NOISE) {
			return BY_NOISE.computeIfAbsent(n, k -> new SiteField(Placements.get().sites(), k, d.temperature(), d.vegetation(),
					d.continents(), d.erosion()));
		}
	}

	/**
	 * The field behind a level's climate sampler, found in its depth function (which contains the site offset),
	 * or null when the dimension has no sites (Nether, End, presets whose terrain is not wrapped).
	 */
	public static SiteField forSampler(Climate.Sampler sampler) {
		Object[] cache = samplers;
		for (int i = 0; i < cache.length; i += 2) {
			if (cache[i] == sampler) {
				return (SiteField) cache[i + 1];
			}
		}
		SiteField[] found = new SiteField[1];
		sampler.depth().mapAll(f -> {
			if (f instanceof SiteDensity s && s.noise().noise() != null && found[0] == null) {
				found[0] = s.field();
			}
			return f;
		});
		synchronized (SiteField.class) {
			Object[] next = new Object[Math.min(samplers.length, 14) + 2];
			System.arraycopy(samplers, 0, next, 2, next.length - 2);
			next[0] = sampler;
			next[1] = found[0];
			samplers = next;
		}
		return found[0];
	}

	public SiteConfig config() {
		return config;
	}

	// ---------------------------------------------------------------------------------------------- placement

	/** The surviving site of cell (gx, gz), or null. */
	public Site site(int gx, int gz) {
		long key = cellKey(gx, gz);
		Object v = resolved.get(key);
		if (v == null) {
			Site s = rawSite(gx, gz);
			if (s != null) {
				int span = (int) Math.ceil(2 * reach / config.cell());
				outer:
				for (int dz = -span; dz <= span; dz++) {
					for (int dx = -span; dx <= span; dx++) {
						if (dx == 0 && dz == 0) {
							continue;
						}
						Site o = rawSite(gx + dx, gz + dz);
						if (o != null && o.priority > s.priority && overlaps(s, o) && site(gx + dx, gz + dz) != null) {
							s = null;
							break outer;
						}
					}
				}
			}
			v = s == null ? NONE : s;
			Object prev = resolved.putIfAbsent(key, v);
			if (prev != null) {
				v = prev;
			}
		}
		return v == NONE ? null : (Site) v;
	}

	private static boolean overlaps(Site a, Site b) {
		double d = Math.hypot(a.x - b.x, a.z - b.z);
		return d < a.core * 1.15 + b.core * 1.15 + 0.5 * (a.ramp + b.ramp);
	}

	private Site rawSite(int gx, int gz) {
		long key = cellKey(gx, gz);
		Object v = raw.get(key);
		if (v == null) {
			Site s = computeRaw(gx, gz);
			v = s == null ? NONE : s;
			Object prev = raw.putIfAbsent(key, v);
			if (prev != null) {
				v = prev;
			}
		}
		return v == NONE ? null : (Site) v;
	}

	private Site computeRaw(int gx, int gz) {
		if (config.specs().isEmpty()) {
			return null;
		}
		long h = mix(seed ^ mix(gx * 0x9E3779B97F4A7C15L + gz * 0xC2B2AE3D27D4EB4FL));
		if (unit(h) >= config.chance()) {
			return null;
		}
		int c = config.cell();
		double j = config.jitter();
		int x = (int) Math.floor((gx + 0.5 + (unit(mix(h + 1)) - 0.5) * j) * c);
		int z = (int) Math.floor((gz + 0.5 + (unit(mix(h + 2)) - 0.5) * j) * c);
		DensityFunction.SinglePointContext at = new DensityFunction.SinglePointContext(x, 64, z);
		double t = temperature.compute(at);
		double hu = vegetation.compute(at);
		double co = continents.compute(at);
		double er = erosion.compute(at);
		double total = 0;
		List<SiteConfig.Spec> candidates = new ArrayList<>();
		for (SiteConfig.Spec s : config.specs()) {
			if (s.accepts(t, hu, co, er)) {
				candidates.add(s);
				total += s.weight();
			}
		}
		if (candidates.isEmpty()) {
			return null;
		}
		double pick = unit(mix(h + 3)) * total;
		SiteConfig.Spec spec = candidates.get(candidates.size() - 1);
		for (SiteConfig.Spec s : candidates) {
			pick -= s.weight();
			if (pick < 0) {
				spec = s;
				break;
			}
		}
		double core = spec.coreMin() + unit(mix(h + 4)) * (spec.coreMax() - spec.coreMin());
		return new Site(x, z, core, spec.ramp(), spec, mix(h + 5), mix(h + 6));
	}

	// ---------------------------------------------------------------------------------------------- sampling

	/** Weight of one site at (x, z) after the domain warp: 1 inside the core, fading to 0 across the ramp. */
	double weight(Site s, double wx, double wz) {
		double dx = wx - s.x;
		double dz = wz - s.z;
		double d = Math.sqrt(dx * dx + dz * dz);
		if (d >= s.outer()) {
			return 0;
		}
		double r = s.core * s.shape(Math.atan2(dz, dx));
		if (d <= r) {
			return 1;
		}
		double t = (d - r) / s.ramp;
		if (t >= 1) {
			return 0;
		}
		return 1 - t * t * (3 - 2 * t);
	}

	/** The (up to two) sites covering (x, z) with their weights. */
	public Blend blend(int x, int z) {
		if (config.specs().isEmpty()) {
			return Blend.NONE;
		}
		double wx = x + config.warp() * noise.getValue(x, 0.0, z);
		double wz = z + config.warp() * noise.getValue(x + 5011.0, 0.0, z - 7027.0);
		int c = config.cell();
		int gx0 = Math.floorDiv((int) (wx - reach), c);
		int gx1 = Math.floorDiv((int) (wx + reach), c);
		int gz0 = Math.floorDiv((int) (wz - reach), c);
		int gz1 = Math.floorDiv((int) (wz + reach), c);
		Site a = null;
		Site b = null;
		double wa = 0;
		double wb = 0;
		for (int gz = gz0; gz <= gz1; gz++) {
			for (int gx = gx0; gx <= gx1; gx++) {
				Site s = site(gx, gz);
				if (s == null) {
					continue;
				}
				double w = weight(s, wx, wz);
				if (w <= 0) {
					continue;
				}
				if (w > wa) {
					b = a;
					wb = wa;
					a = s;
					wa = w;
				} else if (w > wb) {
					b = s;
					wb = w;
				}
			}
		}
		if (a == null) {
			return Blend.NONE;
		}
		double sum = wa + wb;
		if (sum > 1) {
			wa /= sum;
			wb /= sum;
		}
		return new Blend(a, wa, b, wb);
	}

	/** Sites whose centre is within {@code radius} blocks of (x, z), nearest first; optionally only one biome. */
	public List<Site> sitesNear(int x, int z, int radius, String biome) {
		int c = config.cell();
		List<Site> out = new ArrayList<>();
		for (int gz = Math.floorDiv(z - radius, c); gz <= Math.floorDiv(z + radius, c); gz++) {
			for (int gx = Math.floorDiv(x - radius, c); gx <= Math.floorDiv(x + radius, c); gx++) {
				Site s = site(gx, gz);
				if (s != null && (biome == null || s.spec().biome().equals(biome)) && Math.hypot(s.x - x, s.z - z) <= radius) {
					out.add(s);
				}
			}
		}
		out.sort(Comparator.comparingDouble(s -> Math.hypot(s.x - x, s.z - z)));
		return out;
	}

	// ---------------------------------------------------------------------------------------------- hashing

	private static long cellKey(int gx, int gz) {
		return ((long) gx << 32) ^ (gz & 0xFFFFFFFFL);
	}

	static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	static double unit(long h) {
		return (h >>> 11) * 0x1.0p-53;
	}
}
