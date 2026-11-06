package io.github.menonng.biomesmod.terrain.landform;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.menonng.biomesmod.terrain.util.StrataPalette;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code pillar_forest}: a cluster of tall, thin, fluted pillars with flat vegetated caps (Zhangjiajie), or with
 * {@code dome}, {@code notch} and {@code rise_from: sea_level} rounded karst towers standing in water (Ha Long Bay).
 */
public final class PillarForestShape extends LandformShape {
	static final String NAME = "pillar_forest";

	static final MapCodec<PillarForestShape> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Span.CODEC.optionalFieldOf("cluster_radius", Span.of(22, 40)).forGetter(s -> s.clusterRadius),
			Codec.floatRange(3, 64).optionalFieldOf("pillar_spacing", 9f).forGetter(s -> s.pillarSpacing),
			Codec.floatRange(0, 1).optionalFieldOf("density", 0.7f).forGetter(s -> s.density),
			Span.CODEC.optionalFieldOf("pillar_radius", Span.of(2.5, 5)).forGetter(s -> s.pillarRadius),
			Span.CODEC.optionalFieldOf("height", Span.of(35, 80)).forGetter(s -> s.height),
			Codec.floatRange(0, 0.9f).optionalFieldOf("taper", 0.12f).forGetter(s -> s.taper),
			Codec.floatRange(0, 1).optionalFieldOf("flute", 0.22f).forGetter(s -> s.flute),
			Codec.floatRange(0, 2).optionalFieldOf("dome", 0f).forGetter(s -> s.dome),
			Codec.intRange(0, 4).optionalFieldOf("notch", 0).forGetter(s -> s.notch),
			Codec.floatRange(0, 3).optionalFieldOf("talus", 1f).forGetter(s -> s.talusScale)
	).apply(i, PillarForestShape::new));

	final Span clusterRadius;
	final float pillarSpacing;
	final float density;
	final Span pillarRadius;
	final Span height;
	final float taper;
	final float flute;
	final float dome;
	final int notch;
	final float talusScale;

	PillarForestShape(Span clusterRadius, float pillarSpacing, float density, Span pillarRadius, Span height, float taper,
			float flute, float dome, int notch, float talusScale) {
		this.clusterRadius = clusterRadius;
		this.pillarSpacing = pillarSpacing;
		this.density = density;
		this.pillarRadius = pillarRadius;
		this.height = height;
		this.taper = taper;
		this.flute = flute;
		this.dome = dome;
		this.notch = notch;
		this.talusScale = talusScale;
	}

	@Override
	public String name() {
		return NAME;
	}

	private double talusWidth(double r) {
		return talusScale <= 0 ? 0 : (2 + r * 0.9) * talusScale;
	}

	@Override
	int maxReach() {
		double r = pillarRadius.max() * (1 + flute) * 1.15;
		return (int) Math.ceil(clusterRadius.max() * 1.3 + pillarSpacing + r + talusWidth(pillarRadius.max()) + 2);
	}

	@Override
	StrataPalette defaultPalette() {
		return B.strata(null, 0, "sandstone", 3, "andesite", 1, "smooth_sandstone", 2, "tuff", 1, "sandstone", 2, "stone", 1);
	}

	@Override
	List<BlockState> defaultTalus() {
		return B.list("coarse_dirt", "gravel", "cobblestone", "mossy_cobblestone");
	}

	@Override
	int defaultMaxRelief() {
		return 30;
	}

	@Override
	Optional<Cap> defaultCap() {
		return Optional.of(Cap.grass());
	}

	record Pillar(double x, double z, double r, int base, int top, long seed, double talusW, double talusH, double box) {
	}

	@Override
	Landform create(Site site) {
		LNoise.Rng r = site.rng;
		double rc = r.range(clusterRadius);
		int[] ring = site.ring(rc * 0.8, 8);
		if (!site.reliefOk(ring)) {
			return null;
		}
		long outlineSeed = site.sub(11);
		List<Pillar> pillars = new ArrayList<>();
		double ps = pillarSpacing;
		int n = (int) Math.ceil(rc * 1.3 / ps) + 1;
		int reach = 0;
		for (int j = -n; j <= n; j++) {
			for (int i = -n; i <= n; i++) {
				// Offset rows (hex-like) plus strong jitter: no grid alignment survives.
				double px = site.x + (i + 0.5 * (j & 1)) * ps + (r.next() - 0.5) * ps * 0.85;
				double pz = site.z + j * ps * 0.866 + (r.next() - 0.5) * ps * 0.85;
				double roll = r.next();
				double rad = r.range(pillarRadius);
				double hh = r.range(height);
				double hj = r.next();
				long ps2 = r.nextLong();
				double dx = px - site.x;
				double dz = pz - site.z;
				double d = Math.sqrt(dx * dx + dz * dz);
				double th = Math.atan2(dz, dx);
				double edge = rc * (1 + 0.3 * LNoise.angular2(outlineSeed, th, 4));
				double q = d / edge;
				double p = density * (1 - LNoise.smoothstep(0.5, 1.0, q));
				if (roll >= p) {
					continue;
				}
				int base = site.floor(px, pz);
				int rise = site.riseBase(base);
				// Taller towards the cluster centre, but every pillar differs.
				double h = hh * (0.55 + 0.45 * (1 - q * q)) * (0.85 + 0.3 * hj);
				int top = (int) Math.min(rise + h, site.ceiling());
				if (top < base + 6) {
					continue;
				}
				double tw = talusWidth(rad);
				double th2 = talusScale * Math.min(9, 2 + (top - base) * 0.08);
				double box = rad * (1 + flute) * 1.15 + tw + 1;
				pillars.add(new Pillar(px, pz, rad, base, top, ps2, tw, th2, box));
				reach = (int) Math.max(reach, d + box + 1);
			}
		}
		if (pillars.isEmpty()) {
			return null;
		}
		return new Instance(site, reach, pillars);
	}

	final class Instance extends Landform {
		final List<Pillar> pillars;

		Instance(Site s, int reach, List<Pillar> pillars) {
			super(s, reach);
			this.pillars = pillars;
		}

		@Override
		public String describe() {
			return NAME + " at " + cx + "," + cz + ": " + pillars.size() + " pillars";
		}

		@Override
		void column(int x, int z, Col c) {
			int best = Col.NONE;
			boolean capCol = false;
			boolean notchCol = false;
			int talusTop = Col.NONE;
			double px = x + 0.5;
			double pz = z + 0.5;
			for (Pillar p : pillars) {
				double dx = px - p.x;
				double dz = pz - p.z;
				if (Math.abs(dx) > p.box || Math.abs(dz) > p.box) {
					continue;
				}
				double d = Math.sqrt(dx * dx + dz * dz);
				double th = Math.atan2(dz, dx);
				// Vertical flutes: sharp grooves, rounded ribs; plus a coarse irregular outline.
				double nF = Math.max(5, Math.PI * 2 * p.r / 2.3);
				double g = Math.abs(LNoise.angular(p.seed, th, nF));
				double rib = 2 * Math.sqrt(Math.min(1, g * 1.6)) - 1;
				double outline = 1 + flute * (0.45 * rib + 0.55 * LNoise.angular2(p.seed ^ 3, th, 3));
				double rr = p.r * outline;
				double q = d / rr;
				if (q <= 1.08) {
					int t = erodedTop(q, p.base, p.top, 0.07, taper, p.seed ^ 5, 3.5);
					if (t > p.base) {
						int capY = p.top + (int) Math.round(1.1 * LNoise.noise2(p.seed ^ 9, x / 5.0, z / 5.0));
						if (dome > 0) {
							double dh = dome * Math.min(p.r * 1.3, 14) * Math.pow(Math.min(1, q), 2.2);
							capY -= (int) Math.round(dh);
						}
						t = Math.min(t, capY);
						if (t > best) {
							best = t;
							capCol = t >= capY - 2 && q < 0.95;
							notchCol = notch > 0 && d > rr - notch - 0.3 && p.base < sea - 1 && t > sea + 3;
						}
					}
				}
				if (p.talusW > 0) {
					double s = d - p.r;
					if (s < p.talusW) {
						double k = 1 - Math.max(0, s) / p.talusW;
						double h = p.talusH * k * k * (0.6 + 0.4 * (0.5 + 0.5 * LNoise.noise2(p.seed ^ 7, x / 3.0, z / 3.0)));
						talusTop = Math.max(talusTop, p.base + (int) Math.round(h));
					}
				}
			}
			if (best == Col.NONE && talusTop == Col.NONE) {
				return;
			}
			if (best != Col.NONE && best >= talusTop) {
				c.top = best;
				c.kind = K_BODY;
				c.cap = capCol;
				if (notchCol) {
					c.voidLo = sea - 1;
					c.voidHi = sea + 1;
					c.voidWater = sea;
				}
			} else {
				c.top = talusTop;
				c.kind = K_TALUS;
			}
		}
	}
}
