package io.github.menonng.biomesmod.terrain.field;

/**
 * Seeded, allocation-free 2D noise for the terrain shaper: simplex gradient noise with hashed (non-periodic)
 * gradients, fBm with a different rotation per octave (no axis-aligned tells), domain warping, ridged noise and
 * jittered cellular (Worley) noise. Every value is a pure function of seed + coordinates, so neighbouring chunks agree.
 */
public final class Noise {
	private static final double F2 = 0.5 * (Math.sqrt(3.0) - 1.0);
	private static final double G2 = (3.0 - Math.sqrt(3.0)) / 6.0;
	private static final int GRADIENTS = 32;
	private static final double[] GX = new double[GRADIENTS];
	private static final double[] GZ = new double[GRADIENTS];
	private static final int MAX_OCTAVES = 8;
	private static final double[] ROT_C = new double[MAX_OCTAVES];
	private static final double[] ROT_S = new double[MAX_OCTAVES];

	static {
		for (int i = 0; i < GRADIENTS; i++) {
			double a = 2 * Math.PI * (i + 0.37) / GRADIENTS;
			GX[i] = Math.cos(a);
			GZ[i] = Math.sin(a);
		}
		for (int o = 0; o < MAX_OCTAVES; o++) {
			double a = o * 1.1781 + 0.3; // irrational-ish steps, so octave lattices never line up
			ROT_C[o] = Math.cos(a);
			ROT_S[o] = Math.sin(a);
		}
	}

	private final int s1;
	private final int s2;
	private final double ox;
	private final double oz;

	public Noise(long seed) {
		long m = mix(seed);
		this.s1 = (int) m;
		this.s2 = (int) (m >>> 32);
		this.ox = ((mix(m + 1) >>> 11) * 0x1.0p-53) * 4096.0;
		this.oz = ((mix(m + 2) >>> 11) * 0x1.0p-53) * 4096.0;
	}

	/** A derived, independent noise. */
	public Noise fork(int salt) {
		return new Noise(((long) s1 << 32 ^ s2) * 0x9E3779B97F4A7C15L + salt * 0xBF58476D1CE4E5B9L + salt);
	}

	public static long mix(long z) {
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	private int hash(int i, int j) {
		int h = s1 ^ i * 0x27D4EB2D ^ j * 0x165667B1;
		h ^= h >>> 15;
		h *= 0x2C1B3C6D ^ s2;
		h ^= h >>> 12;
		h *= 0x297A2D39;
		h ^= h >>> 15;
		return h;
	}

	/** Uniform value in [0, 1) for integer cell coordinates. */
	public double cell01(int i, int j) {
		return (hash(i, j) >>> 8) * 0x1.0p-24;
	}

	/** Uniform value in [0, 1) for integer cell coordinates and a salt. */
	public double cell01(int i, int j, int salt) {
		return (hash(i + salt * 0x3C6EF372, j ^ salt * 0x0D2B79F5) >>> 8) * 0x1.0p-24;
	}

	/** 2D simplex noise, roughly in [-1, 1]. */
	public double simplex(double x, double z) {
		x += ox;
		z += oz;
		double s = (x + z) * F2;
		int i = floor(x + s);
		int j = floor(z + s);
		double t = (i + j) * G2;
		double x0 = x - (i - t);
		double z0 = z - (j - t);
		int i1;
		int j1;
		if (x0 > z0) {
			i1 = 1;
			j1 = 0;
		} else {
			i1 = 0;
			j1 = 1;
		}
		double x1 = x0 - i1 + G2;
		double z1 = z0 - j1 + G2;
		double x2 = x0 - 1 + 2 * G2;
		double z2 = z0 - 1 + 2 * G2;
		double n = 0;
		double t0 = 0.5 - x0 * x0 - z0 * z0;
		if (t0 > 0) {
			int g = hash(i, j) & (GRADIENTS - 1);
			t0 *= t0;
			n += t0 * t0 * (GX[g] * x0 + GZ[g] * z0);
		}
		double t1 = 0.5 - x1 * x1 - z1 * z1;
		if (t1 > 0) {
			int g = hash(i + i1, j + j1) & (GRADIENTS - 1);
			t1 *= t1;
			n += t1 * t1 * (GX[g] * x1 + GZ[g] * z1);
		}
		double t2 = 0.5 - x2 * x2 - z2 * z2;
		if (t2 > 0) {
			int g = hash(i + 1, j + 1) & (GRADIENTS - 1);
			t2 *= t2;
			n += t2 * t2 * (GX[g] * x2 + GZ[g] * z2);
		}
		return 99.0 * n;
	}

	/** Fractal sum of {@code octaves} simplex layers at base frequency 1/scale, normalised to roughly [-1, 1]. */
	public double fbm(double x, double z, double scale, int octaves) {
		return fbm(x, z, scale, octaves, 0.5);
	}

	public double fbm(double x, double z, double scale, int octaves, double gain) {
		double fx = x / scale;
		double fz = z / scale;
		double sum = 0;
		double amp = 1;
		double norm = 0;
		for (int o = 0; o < octaves; o++) {
			double rx = fx * ROT_C[o] - fz * ROT_S[o];
			double rz = fx * ROT_S[o] + fz * ROT_C[o];
			sum += amp * simplex(rx + o * 31.7, rz - o * 17.3);
			norm += amp;
			amp *= gain;
			fx *= 2.03;
			fz *= 2.03;
		}
		return sum / norm * (octaves > 1 ? 1.35 : 1.0);
	}

	/** fBm mapped to [0, 1]. */
	public double fbm01(double x, double z, double scale, int octaves) {
		return clamp(0.5 + 0.5 * fbm(x, z, scale, octaves), 0, 1);
	}

	/** Ridged noise: 1 at the zero-contours of an fBm field, falling off on both sides. */
	public double ridged(double x, double z, double scale, int octaves) {
		return 1.0 - Math.min(1.0, Math.abs(fbm(x, z, scale, octaves)));
	}

	/**
	 * Jittered cellular noise. Writes {F1, F2, cellX, cellZ, pointX, pointZ} of the nearest feature point into out
	 * (distances in cell units).
	 */
	public void worley(double x, double z, double jitter, double[] out) {
		int ci = floor(x);
		int cj = floor(z);
		double f1 = Double.MAX_VALUE;
		double f2 = Double.MAX_VALUE;
		int bi = 0;
		int bj = 0;
		double bx = 0;
		double bz = 0;
		for (int dj = -1; dj <= 1; dj++) {
			for (int di = -1; di <= 1; di++) {
				int i = ci + di;
				int j = cj + dj;
				double px = i + 0.5 + (cell01(i, j, 1) - 0.5) * jitter;
				double pz = j + 0.5 + (cell01(i, j, 2) - 0.5) * jitter;
				double dx = px - x;
				double dz = pz - z;
				double dd = dx * dx + dz * dz;
				if (dd < f1) {
					f2 = f1;
					f1 = dd;
					bi = i;
					bj = j;
					bx = px;
					bz = pz;
				} else if (dd < f2) {
					f2 = dd;
				}
			}
		}
		out[0] = Math.sqrt(f1);
		out[1] = Math.sqrt(f2);
		out[2] = bi;
		out[3] = bj;
		out[4] = bx;
		out[5] = bz;
	}

	/**
	 * Distance (in cell units) from p to the nearest Voronoi edge, the true perpendicular distance to the bisector of
	 * the two nearest points (unlike F2-F1, it gives constant-width lines).
	 */
	public double voronoiEdge(double x, double z, double jitter) {
		int ci = floor(x);
		int cj = floor(z);
		double bestD = Double.MAX_VALUE;
		double ax = 0;
		double az = 0;
		for (int dj = -1; dj <= 1; dj++) {
			for (int di = -1; di <= 1; di++) {
				int i = ci + di;
				int j = cj + dj;
				double px = i + 0.5 + (cell01(i, j, 1) - 0.5) * jitter;
				double pz = j + 0.5 + (cell01(i, j, 2) - 0.5) * jitter;
				double dd = (px - x) * (px - x) + (pz - z) * (pz - z);
				if (dd < bestD) {
					bestD = dd;
					ax = px;
					az = pz;
				}
			}
		}
		double edge = Double.MAX_VALUE;
		for (int dj = -2; dj <= 2; dj++) {
			for (int di = -2; di <= 2; di++) {
				int i = ci + di;
				int j = cj + dj;
				double px = i + 0.5 + (cell01(i, j, 1) - 0.5) * jitter;
				double pz = j + 0.5 + (cell01(i, j, 2) - 0.5) * jitter;
				double ex = px - ax;
				double ez = pz - az;
				double len2 = ex * ex + ez * ez;
				if (len2 < 1e-9) {
					continue;
				}
				double mx = (px + ax) * 0.5 - x;
				double mz = (pz + az) * 0.5 - z;
				double dist = (mx * ex + mz * ez) / Math.sqrt(len2);
				if (dist < edge) {
					edge = dist;
				}
			}
		}
		return edge;
	}

	public static int floor(double v) {
		int i = (int) v;
		return v < i ? i - 1 : i;
	}

	public static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : v > hi ? hi : v;
	}

	public static double smoothstep(double e0, double e1, double x) {
		double t = clamp((x - e0) / (e1 - e0), 0, 1);
		return t * t * (3 - 2 * t);
	}

	public static double lerp(double a, double b, double t) {
		return a + (b - a) * t;
	}

	/** Polynomial smooth maximum with blend width k. */
	public static double smax(double a, double b, double k) {
		if (k <= 0) {
			return Math.max(a, b);
		}
		double h = Math.max(k - Math.abs(a - b), 0) / k;
		return Math.max(a, b) + h * h * k * 0.25;
	}

	/** Polynomial smooth minimum with blend width k. */
	public static double smin(double a, double b, double k) {
		return -smax(-a, -b, k);
	}
}
