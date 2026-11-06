package io.github.menonng.biomesmod.terrain.landform;

/**
 * Stateless, allocation-free hash noise for landforms. Every value is a pure function of
 * (seed, coordinates), so neighbouring chunks always agree.
 */
final class LNoise {
	private LNoise() {
	}

	private static final double TAU = Math.PI * 2;
	private static final double[] G2X = new double[16];
	private static final double[] G2Y = new double[16];

	static {
		for (int i = 0; i < 16; i++) {
			double a = TAU * (i + 0.5) / 16;
			G2X[i] = Math.cos(a);
			G2Y[i] = Math.sin(a);
		}
	}

	static long mix(long z) {
		z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
		z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
		return z ^ (z >>> 33);
	}

	static long hash(long seed, int x, int z) {
		return mix(seed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL));
	}

	static long hash(long seed, int x, int y, int z) {
		return mix(seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0x165667B19E3779F9L) ^ (z * 0xC2B2AE3D27D4EB4FL));
	}

	/** Derives an independent seed. */
	static long sub(long seed, long salt) {
		return mix(seed + salt * 0x632BE59BD9B4E019L);
	}

	/** Uniform [0, 1) from a hash. */
	static double unit(long h) {
		return (h >>> 11) * 0x1.0p-53;
	}

	static double unit(long seed, int x, int z) {
		return unit(hash(seed, x, z));
	}

	static double unit(long seed, int x, int y, int z) {
		return unit(hash(seed, x, y, z));
	}

	static int floor(double v) {
		int i = (int) v;
		return v < i ? i - 1 : i;
	}

	private static double fade(double t) {
		return t * t * t * (t * (t * 6 - 15) + 10);
	}

	private static double grad2(long seed, int ix, int iy, double dx, double dy) {
		int h = (int) (hash(seed, ix, iy) >>> 60);
		return G2X[h] * dx + G2Y[h] * dy;
	}

	/** 2D gradient noise, roughly in [-1, 1]. */
	static double noise2(long seed, double x, double y) {
		int ix = floor(x);
		int iy = floor(y);
		double fx = x - ix;
		double fy = y - iy;
		double u = fade(fx);
		double v = fade(fy);
		double n00 = grad2(seed, ix, iy, fx, fy);
		double n10 = grad2(seed, ix + 1, iy, fx - 1, fy);
		double n01 = grad2(seed, ix, iy + 1, fx, fy - 1);
		double n11 = grad2(seed, ix + 1, iy + 1, fx - 1, fy - 1);
		double a = n00 + u * (n10 - n00);
		double b = n01 + u * (n11 - n01);
		return (a + v * (b - a)) * 1.414;
	}

	/** 1D gradient noise, roughly in [-1, 1]. */
	static double noise1(long seed, double x) {
		int ix = floor(x);
		double fx = x - ix;
		double g0 = unit(hash(seed, ix, 0)) * 2 - 1;
		double g1 = unit(hash(seed, ix + 1, 0)) * 2 - 1;
		double n0 = g0 * fx;
		double n1 = g1 * (fx - 1);
		return (n0 + fade(fx) * (n1 - n0)) * 2.0;
	}

	/** Fractal sum of 2D noise, normalised to roughly [-1, 1]. */
	static double fbm2(long seed, double x, double y, int octaves) {
		double sum = 0;
		double amp = 1;
		double norm = 0;
		for (int o = 0; o < octaves; o++) {
			sum += amp * noise2(seed + o * 0x51ED27L, x, y);
			norm += amp;
			amp *= 0.5;
			x *= 2.03;
			y *= 2.03;
		}
		return sum / norm;
	}

	/**
	 * Periodic noise around a circle: {@code features} is roughly the number of bumps over 360 degrees.
	 * Sampling a 2D noise on a circle avoids a seam at theta = +-pi.
	 */
	static double angular(long seed, double theta, double features) {
		double r = features / TAU;
		return noise2(seed, Math.cos(theta) * r + 31.7, Math.sin(theta) * r - 17.3);
	}

	/** Two-octave angular noise. */
	static double angular2(long seed, double theta, double features) {
		return 0.7 * angular(seed, theta, features) + 0.3 * angular(seed ^ 0x5DEECE66DL, theta, features * 2.7);
	}

	/** Smallest signed angle difference a - b in (-pi, pi]. */
	static double angleDiff(double a, double b) {
		double d = (a - b) % TAU;
		if (d > Math.PI) {
			d -= TAU;
		} else if (d <= -Math.PI) {
			d += TAU;
		}
		return d;
	}

	static double smoothstep(double e0, double e1, double x) {
		double t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
		return t * t * (3 - 2 * t);
	}

	static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}

	static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	/** Small deterministic seeded RNG for per-instance decisions (never the context random). */
	static final class Rng {
		private long state;

		Rng(long seed) {
			this.state = seed;
		}

		long nextLong() {
			state += 0x9E3779B97F4A7C15L;
			return mix(state);
		}

		double next() {
			return (nextLong() >>> 11) * 0x1.0p-53;
		}

		double range(double lo, double hi) {
			return lo + (hi - lo) * next();
		}

		int nextInt(int bound) {
			return (int) Math.floor(next() * bound);
		}

		boolean chance(double p) {
			return next() < p;
		}

		double range(Span s) {
			return s.min() + (s.max() - s.min()) * next();
		}

		int rangeInt(Span s) {
			return (int) Math.floor(s.min() + (s.max() - s.min() + 0.999) * next());
		}

		double angle() {
			return next() * TAU - Math.PI;
		}
	}
}
