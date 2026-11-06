package io.github.menonng.biomesmod.terrain.field.mode;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.menonng.biomesmod.terrain.field.Coverage;
import io.github.menonng.biomesmod.terrain.field.Noise;

/**
 * Dry crevasse fields on ice: short, roughly parallel cracks in en-echelon sets, inside noise-shaped zones, with a
 * slowly turning orientation. Never water-filled.
 *
 * @param spacing  blocks between parallel cracks
 * @param width    crack width (blocks)
 * @param depth    maximum depth
 * @param coverage fraction of the ice covered by crevasse zones
 * @param scale    size of the zones
 * @param length   typical crack length
 */
public record Crevasses(double spacing, double width, int depth, double coverage, double scale, double length) {
	public static final Codec<Crevasses> CODEC = RecordCodecBuilder.create(i -> i.group(
			Codec.doubleRange(3, 64).optionalFieldOf("spacing", 7.0).forGetter(Crevasses::spacing),
			Codec.doubleRange(0.5, 4).optionalFieldOf("width", 1.2).forGetter(Crevasses::width),
			Codec.intRange(1, 48).optionalFieldOf("depth", 10).forGetter(Crevasses::depth),
			Codec.doubleRange(0, 1).optionalFieldOf("coverage", 0.25).forGetter(Crevasses::coverage),
			Codec.doubleRange(16, 2048).optionalFieldOf("scale", 120.0).forGetter(Crevasses::scale),
			Codec.doubleRange(4, 256).optionalFieldOf("length", 16.0).forGetter(Crevasses::length)
	).apply(i, Crevasses::new));

	/** Crevasse depth at (x, z) in blocks (0 = none). {@code bias} raises the zone strength (steep ice, margins). */
	double depthAt(Noise noise, double x, double z, double bias) {
		double zone = noise.fbm(x, z, scale, 2) + bias;
		double t = Coverage.threshold(coverage, 2);
		double strength = Noise.smoothstep(t, t + 0.18, zone);
		if (strength <= 0) {
			return 0;
		}
		double theta = Math.PI * noise.fbm(x + 913, z - 271, scale * 1.6, 1);
		double c = Math.cos(theta);
		double s = Math.sin(theta);
		double u = x * c + z * s + 2.5 * noise.fbm(x, z, 23, 1);
		double v = -x * s + z * c;
		double k = Math.floor(u / spacing);
		double fu = u - (k + 0.5) * spacing;
		double half = width * 0.5 * (0.7 + 0.6 * noise.cell01((int) k, 5));
		if (Math.abs(fu) > half) {
			return 0;
		}
		double seg = noise.simplex(v / length + k * 3.7, k * 1.3);
		if (seg < 0.1) {
			return 0;
		}
		double taper = Noise.smoothstep(0.1, 0.45, seg);
		return depth * strength * taper * (1 - Math.abs(fu) / (half + 0.5));
	}
}
