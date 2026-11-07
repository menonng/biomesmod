package io.github.menonng.biomesmod.terrain.landform;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.menonng.biomesmod.terrain.util.StrataPalette;

/**
 * {@code cascade}: a horseshoe of waterfalls (Iguazu, the Devil's Throat). A broad shallow river at ground level
 * pours over an irregular U-shaped lip into a plunge basin cut {@code drop} blocks into the ground and leaves down a
 * gorge. Wooded islets on the lip split the curtain into many separate falls. Everything is cut into the existing
 * terrain: only low banks right beside the water are raised, so the landform never stands up as a block. The water is
 * real: the upper river's source blocks spill over the edge (ticks are scheduled), so the falls run as soon as the
 * chunk loads.
 */
public final class CascadeShape extends LandformShape {
	static final String NAME = "cascade";

	static final MapCodec<CascadeShape> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Span.CODEC.optionalFieldOf("radius", Span.of(30, 46)).forGetter(s -> s.radius),
			Span.CODEC.optionalFieldOf("drop", Span.of(18, 28)).forGetter(s -> s.drop),
			Codec.floatRange(0, 1).optionalFieldOf("islets", 0.35f).forGetter(s -> s.islets),
			Span.CODEC.optionalFieldOf("river_width", Span.of(40, 70)).forGetter(s -> s.riverWidth),
			Codec.floatRange(40, 200).optionalFieldOf("arc", 115f).forGetter(s -> s.arc)
	).apply(i, CascadeShape::new));

	static final int K_SHELF = 25;
	/** Width of the raised bank kept beside the river and the basin; farther out the terrain is left alone. */
	static final double BANK = 6;

	final Span radius;
	final Span drop;
	final float islets;
	final Span riverWidth;
	final float arc;

	CascadeShape(Span radius, Span drop, float islets, Span riverWidth, float arc) {
		this.radius = radius;
		this.drop = drop;
		this.islets = islets;
		this.riverWidth = riverWidth;
		this.arc = arc;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	int maxReach() {
		return (int) Math.ceil(radius.max() * 2.6 + 6);
	}

	@Override
	StrataPalette defaultPalette() {
		return B.strata(null, 0, "basalt", 3, "smooth_basalt", 1, "tuff", 2, "basalt", 2);
	}

	@Override
	int defaultMaxRelief() {
		return 20;
	}

	@Override
	Landform create(Site site) {
		LNoise.Rng r = site.rng;
		double rad = r.range(radius);
		int[] ring = site.ring(rad * 1.6, 8);
		if (!site.reliefOk(ring)) {
			return null;
		}
		// The upper river runs at the local ground level; the basin is cut below it.
		int upper = Math.min(Math.max(site.sea + 1, Site.mean(ring, site.floor0)), site.ceiling() - 4);
		int lower = upper - (int) Math.round(r.range(drop));
		return new Instance(site, lower, upper, rad, r.range(riverWidth), r.angle());
	}

	final class Instance extends Landform {
		final int lower;
		final int upper;
		final double rad;
		final double riverW;
		final double dir;
		final double reachIn;

		Instance(Site s, int base, int upper, double rad, double riverW, double dir) {
			super(s, maxReach());
			this.lower = base;
			this.upper = upper;
			this.rad = rad;
			this.riverW = riverW;
			this.dir = dir;
			this.reachIn = maxReach() - 4;
			this.holdsWater = true;
		}

		@Override
		public String describe() {
			return NAME + " at " + cx + "," + cz + ": r=" + Math.round(rad) + " drop=" + (upper - lower);
		}

		/** -1 outside, 0 upper shelf/banks, 1 lower basin/gorge. out = {u, v, d, distance to the basin/gorge edge}. */
		private int zone(double px, double pz, double[] out) {
			double dx = px - cx;
			double dz = pz - cz;
			double u = dx * Math.cos(dir) + dz * Math.sin(dir);    // downstream
			double v = -dx * Math.sin(dir) + dz * Math.cos(dir);
			double d = Math.sqrt(dx * dx + dz * dz);
			double a = Math.atan2(v, u);                           // 0 = downstream
			out[0] = u;
			out[1] = v;
			out[2] = d;
			if (d > reachIn) {
				return -1;
			}
			double lip = rad * (1 + 0.16 * LNoise.angular(seed ^ 0xF1, a, 7) + 0.06 * LNoise.angular(seed ^ 0xF2, a, 19));
			boolean inArc = Math.abs(a) < Math.toRadians(arc);
			double gorgeHalf = rad * 0.55 * (1 + 0.25 * LNoise.noise1(seed ^ 0xF3, u / 25));
			double toBasin = Math.min(inArc ? d - lip : Double.MAX_VALUE,
					u > 0 && u < reachIn - 6 ? Math.abs(v) - gorgeHalf : Double.MAX_VALUE);
			out[3] = toBasin;
			if ((inArc && d < lip) || (u > 0 && Math.abs(v) < gorgeHalf && u < reachIn - 6)) {
				return 1;
			}
			return 0;
		}

		@Override
		void column(int x, int z, Col c) {
			double px = x + 0.5;
			double pz = z + 0.5;
			double[] p = new double[4];
			int zone = zone(px, pz, p);
			if (zone < 0) {
				return;
			}
			double u = p[0];
			double v = p[1];
			double d = p[2];
			if (zone == 1) {
				// Plunge basin, deepest under the falls, shallowing down the gorge; closed at the far end.
				double end = Math.max(0, (u - (reachIn - 14)) / 8);
				c.top = lower - 4 + (int) Math.round(Math.min(3, Math.max(0, u) / 25) + end * 7);
				c.carve = true;
				if (c.top < lower) {
					c.water = lower;
				}
				c.flag |= Col.FLAG_NO_SEAL;   // never wall off the falls
				return;
			}
			// Upper level: a wide shallow river fanning out to the lip, banks beyond it.
			double riverHalf = riverW * 0.5 * (1 + 0.2 * LNoise.noise1(seed ^ 0xF4, u / 30));
			double fanR = rad * 1.9 + 0.3 * riverHalf;
			double toRiver = Math.max(u < 0 ? Math.abs(v) - riverHalf : d - fanR, d - (reachIn - 8));
			boolean river = toRiver < 0;
			double islet = LNoise.noise2(seed ^ 0xF5, px / 9, pz / 9) + 0.4 * LNoise.noise2(seed ^ 0xF6, px / 4, pz / 4);
			if (river && islet < 1 - islets * 1.4) {
				c.top = upper - 2;
				c.water = upper;
				c.kind = K_SHELF;
				c.carve = true;   // the river cuts into higher ground
			} else if (river || Math.min(toRiver, p[3]) < BANK) {
				// Islets and a low bank beside the water: raised only where the ground is lower, never cut.
				c.top = upper + 1 + (int) Math.round(1.5 * Math.max(0, LNoise.noise2(seed ^ 0xF7, px / 13, pz / 13)));
				c.cap = true;
			}
		}

		@Override
		void decorate(ChunkWriter w, Col[] grid) {
			super.decorate(w, grid);
			// Let the river spill over the lip: tick the shelf water that touches the basin.
			for (int dz = 0; dz < 16; dz++) {
				for (int dx = 0; dx < 16; dx++) {
					Col c = grid[ChunkWriter.gridIndex(dx, dz)];
					if (c == null || c.kind != K_SHELF) {
						continue;
					}
					for (int k = 0; k < 4; k++) {
						int nx = dx + (k == 0 ? 1 : k == 1 ? -1 : 0);
						int nz = dz + (k == 2 ? 1 : k == 3 ? -1 : 0);
						Col n = grid[ChunkWriter.gridIndex(nx, nz)];
						if (n != null && (n.flag & Col.FLAG_NO_SEAL) != 0) {
							w.scheduleWaterTick(w.x0 + dx, upper, w.z0 + dz);
							break;
						}
					}
				}
			}
		}
	}
}
