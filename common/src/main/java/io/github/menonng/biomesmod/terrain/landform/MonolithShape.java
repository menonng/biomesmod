package io.github.menonng.biomesmod.terrain.landform;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.menonng.biomesmod.terrain.util.StrataPalette;
import java.util.List;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code monolith}: one huge fluted columnar tower on a talus skirt (Devils Tower), or with {@code count} 2-5 and
 * {@code taper} near 1 a group of sharp spires (Tre Cime di Lavaredo, granite horns). Outlines are fluted by
 * {@code columns} vertical ribs whose depth wanders with height, and walls are eroded in bands, so no two towers
 * share a silhouette.
 */
public final class MonolithShape extends LandformShape {
	static final String NAME = "monolith";

	static final MapCodec<MonolithShape> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Codec.intRange(1, 5).optionalFieldOf("count", 1).forGetter(s -> s.count),
			Span.CODEC.optionalFieldOf("spread", Span.of(14, 22)).forGetter(s -> s.spread),
			Span.CODEC.optionalFieldOf("radius", Span.of(11, 16)).forGetter(s -> s.radius),
			Span.CODEC.optionalFieldOf("height", Span.of(55, 85)).forGetter(s -> s.height),
			Codec.floatRange(0, 1).optionalFieldOf("taper", 0.18f).forGetter(s -> s.taper),
			Codec.floatRange(0, 0.5f).optionalFieldOf("flute", 0.1f).forGetter(s -> s.flute),
			Codec.intRange(0, 80).optionalFieldOf("columns", 36).forGetter(s -> s.columns),
			Codec.floatRange(0, 4).optionalFieldOf("talus", 1.6f).forGetter(s -> s.talus)
	).apply(i, MonolithShape::new));

	final int count;
	final Span spread;
	final Span radius;
	final Span height;
	final float taper;
	final float flute;
	final int columns;
	final float talus;

	MonolithShape(int count, Span spread, Span radius, Span height, float taper, float flute, int columns, float talus) {
		this.count = count;
		this.spread = spread;
		this.radius = radius;
		this.height = height;
		this.taper = taper;
		this.flute = flute;
		this.columns = columns;
		this.talus = talus;
	}

	@Override
	public String name() {
		return NAME;
	}

	private double talusWidth(double r) {
		return talus <= 0 ? 0 : r * 0.8 * talus + 3;
	}

	@Override
	int maxReach() {
		double r = radius.max() * (1 + flute) * 1.1;
		return (int) Math.ceil((count > 1 ? spread.max() : 0) + r + talusWidth(radius.max()) + 3);
	}

	@Override
	StrataPalette defaultPalette() {
		return B.strata(null, 0, "tuff", 4, "andesite", 2, "tuff", 3, "smooth_basalt", 1);
	}

	@Override
	List<BlockState> defaultTalus() {
		return B.list("gravel", "andesite", "cobblestone", "tuff", "coarse_dirt");
	}

	@Override
	int defaultMaxRelief() {
		return 20;
	}

	@Override
	Landform create(Site site) {
		LNoise.Rng r = site.rng;
		int[] ring = site.ring(radius.max() * 1.5, 8);
		if (!site.reliefOk(ring)) {
			return null;
		}
		int base = site.riseBase(Site.mean(ring, site.floor0));
		Instance inst = new Instance(site, base, count);
		double rowAngle = r.angle();
		double s = r.range(spread);
		for (int k = 0; k < count; k++) {
			double off = count == 1 ? 0 : (k - (count - 1) / 2.0) * s * r.range(0.8, 1.15);
			double side = count == 1 ? 0 : r.range(-0.25, 0.25) * s;
			double x = site.x + Math.cos(rowAngle) * off - Math.sin(rowAngle) * side;
			double z = site.z + Math.sin(rowAngle) * off + Math.cos(rowAngle) * side;
			double rad = r.range(radius) * (count > 1 ? r.range(0.75, 1.1) : 1);
			int h = (int) Math.min(r.range(height) * (count > 1 ? r.range(0.75, 1.0) : 1), site.ceiling() - base);
			inst.tower(k, x, z, rad, h, r.next() * Math.PI * 2);
		}
		return inst;
	}

	final class Instance extends Landform {
		final int base;
		final double[] tx;
		final double[] tz;
		final double[] tr;
		final int[] th;
		final double[] tphase;

		Instance(Site s, int base, int n) {
			super(s, maxReach());
			this.base = base;
			tx = new double[n];
			tz = new double[n];
			tr = new double[n];
			th = new int[n];
			tphase = new double[n];
		}

		void tower(int k, double x, double z, double r, int h, double phase) {
			tx[k] = x;
			tz[k] = z;
			tr[k] = r;
			th[k] = h;
			tphase[k] = phase;
		}

		@Override
		public String describe() {
			return NAME + " at " + cx + "," + cz + ": " + tx.length + " tower(s), h=" + th[0] + " r=" + Math.round(tr[0]);
		}

		/** Radius of tower k at angle a and height fraction t (0 base .. 1 top). */
		private double radiusAt(int k, double a, double t) {
			double rib = columns <= 0 ? 0 : Math.abs(Math.sin(a * columns * 0.5 + tphase[k] + 0.6 * LNoise.noise1(seed ^ (0x31 + k), t * 3)));
			double lobes = 0.08 * LNoise.angular(seed ^ (0x41 + k), a, 3);
			double shrink = 1 - taper * t - (taper > 0.6 ? 0.25 * t * t : 0);
			return tr[k] * (1 + lobes - flute * rib) * Math.max(0.05, shrink);
		}

		@Override
		void column(int x, int z, Col c) {
			double px = x + 0.5;
			double pz = z + 0.5;
			int bodyTop = Col.NONE;
			double talusTop = Double.NEGATIVE_INFINITY;
			for (int k = 0; k < tx.length; k++) {
				double dx = px - tx[k];
				double dz = pz - tz[k];
				double d = Math.sqrt(dx * dx + dz * dz);
				double a = Math.atan2(dz, dx);
				double r0 = radiusAt(k, a, 0);
				if (d <= r0) {
					// Walk up while the column stays inside the shrinking, fluted outline.
					int top = base;
					int h = th[k];
					for (int y = 0; y <= h; y++) {
						double t = y / (double) h;
						if (d > radiusAt(k, a, t)) {
							break;
						}
						top = base + y;
					}
					// A slightly domed, broken summit.
					top += (int) Math.round(1.2 * LNoise.noise2(seed ^ 0x51, px / 5, pz / 5));
					bodyTop = Math.max(bodyTop, top);
				}
				double tw = talusWidth(tr[k]);
				if (tw > 0 && d < r0 + tw) {
					double lobe = 0.55 + 0.45 * (0.5 + 0.5 * LNoise.angular(seed ^ (0x53 + k), a, 6)) + 0.15 * LNoise.noise2(seed ^ 0x54, px / 6, pz / 6);
					double kk = 1 - Math.max(0, d - r0 * 0.9) / (tw * lobe);
					double skirt = th[k] * 0.22 * MonolithShape.this.talus / 1.6 * Math.pow(Math.max(0, kk), 1.6)
							+ 1.2 * LNoise.noise2(seed ^ 0x52, px / 4, pz / 4);
					talusTop = Math.max(talusTop, base + skirt);
				}
			}
			int skirtTop = talusTop == Double.NEGATIVE_INFINITY ? Col.NONE : (int) Math.round(talusTop);
			if (bodyTop != Col.NONE && bodyTop >= skirtTop) {
				c.top = bodyTop;
				c.kind = K_BODY;
			} else if (skirtTop != Col.NONE && skirtTop > base - 1) {
				c.top = skirtTop;
				c.kind = K_TALUS;
			}
		}
	}
}
