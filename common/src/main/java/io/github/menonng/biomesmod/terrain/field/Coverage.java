package io.github.menonng.biomesmod.terrain.field;

/**
 * Maps an area fraction ("coverage") to a threshold on {@link Noise#fbm} so that roughly that fraction of the
 * plane lies above it. The fBm spread is measured once per octave count.
 */
public final class Coverage {
	private static final double[] STD = new double[9];

	static {
		Noise n = new Noise(0x5EEDL);
		for (int oct = 1; oct < STD.length; oct++) {
			double sum = 0;
			double sum2 = 0;
			int count = 0;
			for (int j = 0; j < 96; j++) {
				for (int i = 0; i < 96; i++) {
					double v = n.fbm(i * 13.7, j * 13.7, 9.0, oct);
					sum += v;
					sum2 += v * v;
					count++;
				}
			}
			double mean = sum / count;
			STD[oct] = Math.sqrt(Math.max(1e-6, sum2 / count - mean * mean));
		}
	}

	private Coverage() {
	}

	/** Threshold t such that P(fbm(octaves) > t) is about coverage. */
	public static double threshold(double coverage, int octaves) {
		double c = Noise.clamp(coverage, 1e-4, 1 - 1e-4);
		return STD[Math.max(1, Math.min(8, octaves))] * normalQuantile(1 - c);
	}

	/** Standard deviation of fbm with the given octave count. */
	public static double std(int octaves) {
		return STD[Math.max(1, Math.min(8, octaves))];
	}

	/** Inverse standard normal CDF (Abramowitz and Stegun 26.2.23, error below 5e-4). */
	public static double normalQuantile(double p) {
		if (p <= 0) {
			return -8;
		}
		if (p >= 1) {
			return 8;
		}
		boolean lower = p < 0.5;
		double q = lower ? p : 1 - p;
		double t = Math.sqrt(-2 * Math.log(q));
		double z = t - (2.515517 + 0.802853 * t + 0.010328 * t * t) / (1 + 1.432788 * t + 0.189269 * t * t + 0.001308 * t * t * t);
		return lower ? -z : z;
	}
}
