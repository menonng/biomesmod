package io.github.menonng.biomesmod.terrain.landform;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.menonng.biomesmod.terrain.util.StrataPalette;
import java.util.List;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code hot_spring}: a geothermal pool on a low sinter apron. The pool is deepest and bluest in the middle; around
 * it, bands of coloured microbial mats ({@code bands}, inner to outer) streak outward along radial runoff channels
 * (Grand Prismatic Spring). With {@code pools} &gt; 1 it becomes a field of small acid pools with salt chimneys
 * (Dallol: give yellow/green/white bands). Outlines are lobed, never circles.
 */
public final class HotSpringShape extends LandformShape {
	static final String NAME = "hot_spring";

	static final MapCodec<HotSpringShape> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Codec.intRange(1, 30).optionalFieldOf("pools", 1).forGetter(s -> s.pools),
			Span.CODEC.optionalFieldOf("pool_radius", Span.of(10, 18)).forGetter(s -> s.poolRadius),
			Codec.floatRange(0.5f, 6).optionalFieldOf("band_width", 2.2f).forGetter(s -> s.bandWidth),
			Span.CODEC.optionalFieldOf("spread", Span.of(10, 30)).forGetter(s -> s.spread),
			Codec.intRange(1, 12).optionalFieldOf("depth", 5).forGetter(s -> s.depth),
			BlockState.CODEC.listOf().optionalFieldOf("bands").forGetter(s -> s.bands),
			Codec.floatRange(0, 1).optionalFieldOf("chimneys", 0f).forGetter(s -> s.chimneys)
	).apply(i, HotSpringShape::new));

	static final int K_BAND = 23;
	static final int K_CHIMNEY = 24;

	final int pools;
	final Span poolRadius;
	final float bandWidth;
	final Span spread;
	final int depth;
	final java.util.Optional<List<BlockState>> bands;
	final float chimneys;

	HotSpringShape(int pools, Span poolRadius, float bandWidth, Span spread, int depth, java.util.Optional<List<BlockState>> bands,
			float chimneys) {
		this.pools = pools;
		this.poolRadius = poolRadius;
		this.bandWidth = bandWidth;
		this.spread = spread;
		this.depth = depth;
		this.bands = bands;
		this.chimneys = chimneys;
	}

	private List<BlockState> bandList() {
		return bands.orElseGet(() -> B.list("light_blue_terracotta", "lime_terracotta", "yellow_terracotta", "orange_terracotta",
				"red_terracotta", "brown_terracotta"));
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	int maxReach() {
		return (int) Math.ceil((pools > 1 ? spread.max() : 0) + poolRadius.max() * 1.3 + bandWidth * 7 + 6);
	}

	@Override
	StrataPalette defaultPalette() {
		return B.strata("white_terracotta", 1, "calcite", 2, "diorite", 1, "calcite", 2);
	}

	@Override
	int defaultMaxRelief() {
		return 10;
	}

	@Override
	Landform create(Site site) {
		LNoise.Rng r = site.rng;
		int[] ring = site.ring(poolRadius.max() + bandWidth * 6, 8);
		if (!site.reliefOk(ring)) {
			return null;
		}
		int base = Math.max(site.sea, Site.mean(ring, site.floor0));
		Instance inst = new Instance(site, base, pools);
		double s = r.range(spread);
		for (int k = 0; k < pools; k++) {
			double a = r.angle();
			double d = pools == 1 ? 0 : s * Math.sqrt(r.next());
			double rad = r.range(poolRadius) * (pools > 1 ? r.range(0.4, 1.0) : 1);
			inst.pool(k, site.x + Math.cos(a) * d, site.z + Math.sin(a) * d, rad);
		}
		return inst;
	}

	final class Instance extends Landform {
		final int base;
		final double[] px;
		final double[] pz;
		final double[] pr;

		Instance(Site s, int base, int n) {
			super(s, maxReach());
			this.base = base;
			px = new double[n];
			pz = new double[n];
			pr = new double[n];
			this.holdsWater = true;
		}

		void pool(int k, double x, double z, double r) {
			px[k] = x;
			pz[k] = z;
			pr[k] = r;
		}

		@Override
		void column(int x, int z, Col c) {
			double fx = x + 0.5;
			double fz = z + 0.5;
			double best = Double.MAX_VALUE;   // normalised distance to the nearest pool rim (0 centre, 1 rim)
			double bestA = 0;
			for (int k = 0; k < px.length; k++) {
				double dx = fx - px[k];
				double dz = fz - pz[k];
				double a = Math.atan2(dz, dx);
				double rr = pr[k] * (1 + 0.18 * LNoise.angular(seed ^ (0xC1 + k), a, 5) + 0.07 * LNoise.angular(seed ^ (0xD1 + k), a, 13));
				double q = Math.sqrt(dx * dx + dz * dz) / rr;
				if (q < best) {
					best = q;
					bestA = a;
				}
			}
			int nb = bandList().size();
			double bandsOut = bandWidth * nb;   // in blocks beyond the rim (scaled by pool radius below)
			double rimR = pr[0];
			double outside = (best - 1) * rimR;
			// Radial runoff streaks push the bands further out in fingers.
			double streak = Math.max(0, LNoise.angular(seed ^ 0xE1, bestA + 0.2 * LNoise.noise2(seed ^ 0xE2, fx / 9, fz / 9), 18));
			double reach = bandsOut * (0.8 + 1.4 * streak * streak);
			if (best < 1) {
				c.top = base - 1 - (int) Math.round((depth - 1) * Math.pow(1 - best, 0.7));
				c.carve = true;
				c.water = base;
				c.kind = K_BAND;
				c.aux = 0;           // innermost band colours the pool bottom
				c.paint = 1;
			} else if (outside < reach + 3) {
				c.top = base;
				c.carve = true;
				c.paint = 1;
				int band = (int) Math.floor(outside / Math.max(0.5, reach) * nb);
				if (band < nb) {
					c.kind = K_BAND;
					c.aux = Math.max(0, Math.min(nb - 1, band));
				} else {
					c.kind = K_FLOOR;
				}
				if (chimneys > 0 && outside > 1 && LNoise.unit(seed ^ 0xE3, x, z) < chimneys * 0.04) {
					c.top = base + 1 + (int) (LNoise.unit(seed ^ 0xE4, x, z) * 3);
					c.kind = K_CHIMNEY;
				}
			}
		}

		@Override
		BlockState block(int x, int y, int z, int depth, Col c, boolean wet) {
			if (c.kind == K_BAND && depth == 0) {
				return bandList().get(c.aux);
			}
			if (c.kind == K_CHIMNEY) {
				return B.of("white_terracotta");
			}
			return palette.blockAt(y, depth);
		}
	}
}
