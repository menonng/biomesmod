package io.github.menonng.biomesmod.terrain.field.mode;

import io.github.menonng.biomesmod.terrain.field.Noise;

/**
 * A family of roughly parallel, meandering centre lines (canyons, glacial valleys, fjords). The lines are the level
 * sets {@code across + shift(along, across) = k * spacing}. The shift's derivative across the lines stays well below 1,
 * so each line is a graph over the along axis: lines never fold back into loops, rings or crescents.
 */
final class Meander {
	private final double cos;
	private final double sin;
	private final double spacing;
	private final double amp;
	private final double wavelength;
	private final Noise noise;
	private final double offset;

	Meander(Noise noise, double directionDeg, double spacing, double amp, double wavelength) {
		double a = Math.toRadians(directionDeg);
		this.cos = Math.cos(a);
		this.sin = Math.sin(a);
		this.spacing = Math.max(32, spacing);
		// keep |d shift / d across| <= ~0.35 whatever the user sets
		this.amp = Math.min(Math.max(0, amp), this.spacing * 0.2);
		this.wavelength = Math.max(32, wavelength);
		this.noise = noise;
		this.offset = noise.cell01(17, 29) * this.spacing;
	}

	/** out[0] signed perpendicular distance to the nearest line, out[1] along coordinate, out[2] line index. */
	void eval(double x, double z, double[] out) {
		double along = x * cos + z * sin;
		double across = -x * sin + z * cos + offset;
		double c = coord(along, across);
		double k = Math.floor(c / spacing + 0.5);
		double dc = c - k * spacing;
		double h = 1.5;
		double ga = (coord(along + h, across) - coord(along - h, across)) / (2 * h);
		double gc = (coord(along, across + h) - coord(along, across - h)) / (2 * h);
		double grad = Math.max(0.3, Math.sqrt(ga * ga + gc * gc));
		out[0] = dc / grad;
		out[1] = along;
		out[2] = k;
	}

	private double coord(double along, double across) {
		double s = across / (spacing * 0.8);
		return across + amp * (0.72 * noise.simplex(along / wavelength, s) + 0.28 * noise.simplex(along / (wavelength * 0.41), s + 7.1));
	}
}
