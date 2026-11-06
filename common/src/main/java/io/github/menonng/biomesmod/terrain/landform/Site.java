package io.github.menonng.biomesmod.terrain.landform;

import io.github.menonng.biomesmod.compat.LandformCompat;
import io.github.menonng.biomesmod.compat.LevelCompat;
import io.github.menonng.biomesmod.terrain.util.WorldSampler;

/**
 * Everything a shape may use to build one instance: the centre, a seeded RNG, and noise-only terrain samples.
 * Only valid while the instance is being created; instances must not keep it (it references a chunk region).
 */
final class Site {
	final WorldSampler sampler;
	final LandformConfig config;
	final long seed;
	final int x;
	final int z;
	final int floor0;
	final int sea;
	final int minY;
	final int maxY;
	final LNoise.Rng rng;

	Site(WorldSampler sampler, LandformConfig config, long seed, int x, int z, int floor0) {
		this.sampler = sampler;
		this.config = config;
		this.seed = seed;
		this.x = x;
		this.z = z;
		this.floor0 = floor0;
		this.sea = sampler.seaLevel();
		this.minY = LevelCompat.minY(sampler.level());
		this.maxY = LandformCompat.maxY(sampler.level());
		this.rng = new LNoise.Rng(LNoise.sub(seed, 0x51732));
	}

	/** Noise-only top solid block Y at a position (no features, no carvers). */
	int floor(double px, double pz) {
		return sampler.baseFloorHeight(LNoise.floor(px), LNoise.floor(pz)) - 1;
	}

	/** Floors at {@code n} points on a circle around the centre. */
	int[] ring(double radius, int n) {
		int[] out = new int[n];
		for (int i = 0; i < n; i++) {
			double a = Math.PI * 2 * i / n;
			out[i] = floor(x + Math.cos(a) * radius, z + Math.sin(a) * radius);
		}
		return out;
	}

	static int mean(int[] v, int extra) {
		long s = extra;
		for (int a : v) {
			s += a;
		}
		return (int) Math.round(s / (double) (v.length + 1));
	}

	static int min(int[] v, int extra) {
		int m = extra;
		for (int a : v) {
			m = Math.min(m, a);
		}
		return m;
	}

	static int max(int[] v, int extra) {
		int m = extra;
		for (int a : v) {
			m = Math.max(m, a);
		}
		return m;
	}

	/** True if the footprint samples (plus the centre) vary by no more than max_relief. */
	boolean reliefOk(int[] samples) {
		return max(samples, floor0) - min(samples, floor0) <= config.maxRelief();
	}

	/** Base used for heights: the floor, or the sea surface when rise_from = sea_level and the floor is lower. */
	int riseBase(int base) {
		return config.riseFromSea() ? Math.max(base, sea) : base;
	}

	/** Highest Y a landform top may reach (keeps a margin below the build limit). */
	int ceiling() {
		return maxY - 6;
	}

	long sub(long salt) {
		return LNoise.sub(seed, salt);
	}
}
