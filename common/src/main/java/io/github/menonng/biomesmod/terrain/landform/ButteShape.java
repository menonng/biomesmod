package io.github.menonng.biomesmod.terrain.landform;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.menonng.biomesmod.terrain.util.StrataPalette;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code butte}: flat-topped, steep-walled, layered buttes and mesas on a sloping pedestal and talus apron,
 * optionally with a detached "mitten" thumb (Monument Valley); with {@code dome} and {@code clefts}, rounded
 * sandstone inselbergs split by slot canyons (Wadi Rum).
 */
public final class ButteShape extends LandformShape {
	static final String NAME = "butte";

	static final MapCodec<ButteShape> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Span.CODEC.optionalFieldOf("radius", Span.of(14, 28)).forGetter(s -> s.radius),
			Span.CODEC.optionalFieldOf("elongation", Span.of(1.0, 2.0)).forGetter(s -> s.elongation),
			Span.CODEC.optionalFieldOf("height", Span.of(30, 60)).forGetter(s -> s.height),
			Codec.floatRange(0.1f, 1).optionalFieldOf("cliff", 0.55f).forGetter(s -> s.cliff),
			Codec.floatRange(0, 4).optionalFieldOf("pedestal", 1f).forGetter(s -> s.pedestal),
			Codec.floatRange(0, 1.5f).optionalFieldOf("dome", 0f).forGetter(s -> s.dome),
			Codec.floatRange(0, 1).optionalFieldOf("thumbs", 0f).forGetter(s -> s.thumbs),
			Codec.floatRange(0, 1).optionalFieldOf("clefts", 0f).forGetter(s -> s.clefts),
			Codec.floatRange(0, 0.6f).optionalFieldOf("roughness", 0.22f).forGetter(s -> s.roughness),
			StrataPalette.CODEC.optionalFieldOf("pedestal_palette").forGetter(s -> s.pedestalPalette)
	).apply(i, ButteShape::new));

	final Span radius;
	final Span elongation;
	final Span height;
	final float cliff;
	final float pedestal;
	final float dome;
	final float thumbs;
	final float clefts;
	final float roughness;
	final Optional<StrataPalette> pedestalPalette;

	ButteShape(Span radius, Span elongation, Span height, float cliff, float pedestal, float dome, float thumbs, float clefts,
			float roughness, Optional<StrataPalette> pedestalPalette) {
		this.radius = radius;
		this.elongation = elongation;
		this.height = height;
		this.cliff = cliff;
		this.pedestal = pedestal;
		this.dome = dome;
		this.thumbs = thumbs;
		this.clefts = clefts;
		this.roughness = roughness;
		this.pedestalPalette = pedestalPalette;
	}

	@Override
	public String name() {
		return NAME;
	}

	private double pedRun(double h) {
		return pedestal * h * (1 - cliff) * 1.6 + 3;
	}

	@Override
	int maxReach() {
		double r = radius.max() * Math.sqrt(elongation.max()) * (1 + roughness) * 1.35 + radius.max() * roughness * 0.7;
		return (int) Math.ceil(r + pedRun(height.max()) + 4 + radius.max() * 0.15 + 2);
	}

	@Override
	StrataPalette defaultPalette() {
		return B.strata(null, 0, "orange_terracotta", 4, "red_terracotta", 3, "terracotta", 1, "orange_terracotta", 2,
				"red_terracotta", 2, "brown_terracotta", 1);
	}

	@Override
	List<BlockState> defaultTalus() {
		return B.list("red_sand", "red_sand", "coarse_dirt", "red_terracotta");
	}

	@Override
	int defaultMaxRelief() {
		return 18;
	}

	@Override
	Landform create(Site site) {
		LNoise.Rng r = site.rng;
		double rad = r.range(radius);
		double e = r.range(elongation);
		double phi = r.angle();
		double h = r.range(height);
		int[] ring = site.ring(rad * 1.4, 8);
		if (!site.reliefOk(ring)) {
			return null;
		}
		int base = Site.mean(ring, site.floor0);
		int top = (int) Math.min(base + h, site.ceiling());
		Instance inst = new Instance(site, base, top, rad, e, phi);
		if (r.chance(thumbs)) {
			double a = r.angle();
			double rt = rad * r.range(0.17, 0.25);
			double ht = (top - base) * r.range(0.82, 0.97);
			// March out along a to the outline, then stand the thumb astride it.
			double b = 0;
			for (double s = 0; s < rad * 3; s += 0.5) {
				double[] qs = inst.q(site.x + Math.cos(a) * s, site.z + Math.sin(a) * s);
				if (qs[0] > 1) {
					b = s;
					break;
				}
			}
			if (b > rt * 2) {
				inst.thumb(site.x + Math.cos(a) * (b - rt * 0.45), site.z + Math.sin(a) * (b - rt * 0.45), rt, base + (int) ht);
			}
		}
		return inst;
	}

	final class Instance extends Landform {
		final int base;
		final int top;
		final double rad;
		final double sqrtE;
		final double cos;
		final double sin;
		final int cliffBottom;
		final double pedRun;
		final double talusW;
		final StrataPalette pedPalette;
		double tx = Double.NaN;
		double tz;
		double tr;
		int tTop;

		Instance(Site s, int base, int top, double rad, double e, double phi) {
			super(s, maxReach());
			this.base = base;
			this.top = top;
			this.rad = rad;
			this.sqrtE = Math.sqrt(e);
			this.cos = Math.cos(phi);
			this.sin = Math.sin(phi);
			this.cliffBottom = base + (int) Math.round((top - base) * (1 - cliff));
			this.pedRun = pedRun(top - base);
			this.talusW = 4 + rad * 0.15;
		this.pedPalette = pedestalPalette.orElseGet(() -> B.strata(null, 0, "red_terracotta", 2, "brown_terracotta", 1,
					"red_terracotta", 1, "light_gray_terracotta", 1));
		}

		void thumb(double x, double z, double r, int t) {
			tx = x;
			tz = z;
			tr = r;
			tTop = t;
		}

		@Override
		public String describe() {
			return NAME + " at " + cx + "," + cz + ": r=" + Math.round(rad) + " h=" + (top - base) + (Double.isNaN(tx) ? "" : " +thumb");
		}

		/** {q (1 = cliff outline), signed distance outside the outline in blocks, world angle}. */
		double[] q(double px, double pz) {
			double dx = px - cx;
			double dz = pz - cz;
			double amp = rad * roughness * 0.7;
			double f = 1 / (rad * 0.9);
			double wx = dx + amp * LNoise.noise2(seed ^ 0x11, px * f, pz * f);
			double wz = dz + amp * LNoise.noise2(seed ^ 0x12, px * f, pz * f);
			double u = (wx * cos + wz * sin) / sqrtE;
			double v = (-wx * sin + wz * cos) * sqrtE;
			double d = Math.sqrt(u * u + v * v);
			double th = Math.atan2(v, u);
			double rth = rad * (1 + roughness * (0.6 * LNoise.angular(seed ^ 0x13, th, 5) + 0.4 * LNoise.angular(seed ^ 0x14, th, 14)));
			return new double[] {d / rth, d - rth, Math.atan2(dz, dx)};
		}

		@Override
		void column(int x, int z, Col c) {
			double px = x + 0.5;
			double pz = z + 0.5;
			double[] qs = q(px, pz);
			double q = qs[0];
			double out = Math.max(0, qs[1]);
			int capY = top + (int) Math.round(0.8 * LNoise.noise2(seed ^ 0x15, x / 9.0, z / 9.0));
			if (dome > 0) {
				capY -= (int) Math.round(dome * (top - base) * 0.5 * Math.pow(Math.min(1, q), 2));
			}
			boolean inCleft = false;
			if (clefts > 0 && q < 1.02) {
				double cl = Math.abs(LNoise.noise2(seed ^ 0x16, px / (rad * 0.55), pz / (rad * 0.55))
						+ 0.12 * LNoise.noise2(seed ^ 0x17, px / 6.0, pz / 6.0));
				double w = clefts * 0.09;
				if (cl < w) {
					int floorC = cliffBottom - (int) ((cliffBottom - base) * 0.4);
					capY = floorC + (int) ((capY - floorC) * Math.pow(cl / w, 3));
					inCleft = true;
				}
			}
			int mainTop = q <= 1.06 ? erodedTop(q, cliffBottom, capY, 0.05, 0.03, seed ^ 0x18, 3.5) : Col.NONE;
			if (mainTop != Col.NONE && mainTop < cliffBottom) {
				mainTop = Col.NONE;
			}
			int thumbTop = Col.NONE;
			if (!Double.isNaN(tx)) {
				double dx = px - tx;
				double dz = pz - tz;
				double dt = Math.sqrt(dx * dx + dz * dz);
				double th = Math.atan2(dz, dx);
				double rt = tr * (1 + 0.2 * LNoise.angular2(seed ^ 0x19, th, 4));
				if (dt < rt + 2.6 && mainTop != Col.NONE) {
					// Notch around the thumb: the body drops to a low saddle there, so the thumb stands free.
					mainTop = Math.min(mainTop, cliffBottom + (int) ((top - cliffBottom) * 0.28));
				}
				if (dt / rt <= 1.06) {
					int tc = tTop + (int) Math.round(0.8 * LNoise.noise2(seed ^ 0x1A, x / 4.0, z / 4.0));
					int t = erodedTop(dt / rt, cliffBottom, tc, 0.06, 0.15, seed ^ 0x1B, 3.0);
					if (t >= cliffBottom) {
						thumbTop = t;
					}
				}
			}
			int pedTop;
			if (q <= 1) {
				pedTop = cliffBottom;
			} else if (out < pedRun) {
				double k = 1 - out / pedRun;
				double gully = Math.pow(1 - Math.min(1, Math.abs(LNoise.angular(seed ^ 0x1C, qs[2]
						+ 0.25 * LNoise.noise2(seed ^ 0x1D, px / 14, pz / 14), 30)) * 1.7), 4);
				double ph = (cliffBottom - base) * Math.pow(k, 1.35) * (1 - 0.3 * gully * (1 - k))
						+ 0.8 * LNoise.noise2(seed ^ 0x1E, px / 5, pz / 5);
				pedTop = base + (int) Math.round(ph);
			} else if (out < pedRun + talusW) {
				double k = 1 - (out - pedRun) / talusW;
				pedTop = base + (int) Math.round(2 * k * (0.6 + 0.4 * LNoise.noise2(seed ^ 0x1F, px / 3, pz / 3)));
			} else {
				pedTop = Col.NONE;
			}
			int body = Math.max(mainTop, thumbTop);
			if (body != Col.NONE && body >= pedTop) {
				c.top = body;
				c.kind = K_BODY;
				c.cap = !inCleft && body >= (thumbTop == body ? tTop - 2 : capY - 1) && (q < 0.96 || thumbTop == body);
			} else if (pedTop != Col.NONE) {
				c.top = pedTop;
				c.kind = out < pedRun ? K_PEDESTAL : K_TALUS;
			}
		}

		@Override
		BlockState block(int x, int y, int z, int depth, Col c, boolean wet) {
			if (c.kind == K_PEDESTAL) {
				if (depth == 0 && !wet && LNoise.noise2(seed ^ 0x21, x / 4.0, z / 4.0) > -0.1) {
					return talusBlock(x, y, z);
				}
				return pedPalette.strataAt(y);
			}
			return super.block(x, y, z, depth, c, wet);
		}
	}
}
