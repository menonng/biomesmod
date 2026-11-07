package io.github.menonng.biomesmod.terrain.landform;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.menonng.biomesmod.terrain.util.StrataPalette;
import java.util.Optional;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code caldera}: a collapsed volcano. An irregular, uneven ring rim (steep inner wall, gentle outer flank) around
 * a floor that is
 * <ul>
 * <li>{@code lake}: a crater lake below the rim (Lake Koltsevoye), optionally with a central cone island (Krenitsyn);</li>
 * <li>{@code sea}: sunk below sea level and breached on one side so the sea floods in (Ushishir's Kraternaya Bay);</li>
 * <li>{@code sand}: a flat sand sea (Tengger), optionally with a cone standing on it (Batok, Bromo).</li>
 * </ul>
 * The rim radius and crest height change continuously around the ring, so it never reads as a circle.
 */
public final class CalderaShape extends LandformShape {
	static final String NAME = "caldera";

	static final MapCodec<CalderaShape> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Span.CODEC.optionalFieldOf("radius", Span.of(55, 85)).forGetter(s -> s.radius),
			Span.CODEC.optionalFieldOf("rim_height", Span.of(25, 45)).forGetter(s -> s.rimHeight),
			Span.CODEC.optionalFieldOf("flank", Span.of(30, 50)).forGetter(s -> s.flank),
			Codec.STRING.optionalFieldOf("floor", "lake").forGetter(s -> s.floor),
			Codec.intRange(0, 40).optionalFieldOf("floor_depth", 8).forGetter(s -> s.floorDepth),
			Codec.floatRange(0, 1).optionalFieldOf("cone", 0f).forGetter(s -> s.cone),
			Span.CODEC.optionalFieldOf("cone_radius", Span.of(14, 22)).forGetter(s -> s.coneRadius),
			Span.CODEC.optionalFieldOf("cone_height", Span.of(18, 32)).forGetter(s -> s.coneHeight),
			Codec.floatRange(0.1f, 2).optionalFieldOf("breach_width", 0.5f).forGetter(s -> s.breachWidth),
			BlockState.CODEC.optionalFieldOf("floor_block").forGetter(s -> s.floorBlock)
	).apply(i, CalderaShape::new));

	static final int K_CONE = 21;
	static final int K_SAND = 22;
	static final int K_FLANK = 26;

	final Span radius;
	final Span rimHeight;
	final Span flank;
	final String floor;
	final int floorDepth;
	final float cone;
	final Span coneRadius;
	final Span coneHeight;
	final float breachWidth;
	final Optional<BlockState> floorBlock;

	CalderaShape(Span radius, Span rimHeight, Span flank, String floor, int floorDepth, float cone, Span coneRadius, Span coneHeight,
			float breachWidth, Optional<BlockState> floorBlock) {
		this.radius = radius;
		this.rimHeight = rimHeight;
		this.flank = flank;
		this.floor = floor;
		this.floorDepth = floorDepth;
		this.cone = cone;
		this.coneRadius = coneRadius;
		this.coneHeight = coneHeight;
		this.breachWidth = breachWidth;
		this.floorBlock = floorBlock;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	int maxReach() {
		return (int) Math.ceil(radius.max() * 1.25 + flank.max() + 4);
	}

	@Override
	StrataPalette defaultPalette() {
		return B.strata(null, 0, "tuff", 3, "basalt", 2, "andesite", 2, "tuff", 2, "blackstone", 1);
	}

	@Override
	Span defaultBaseRange() {
		return Span.of(-60, 200);
	}

	@Override
	int defaultMaxRelief() {
		return 400;
	}

	@Override
	Landform create(Site site) {
		LNoise.Rng r = site.rng;
		double rad = r.range(radius);
		int base = Site.mean(site.ring(rad, 8), site.floor0);
		int foot = "sea".equals(floor) ? base : site.riseBase(base);
		int crest = (int) Math.min(Math.max(foot, site.sea) + r.range(rimHeight), site.ceiling());
		Instance inst = new Instance(site, base, foot, crest, rad, r.range(flank), r.angle());
		if (r.chance(cone)) {
			double a = r.angle();
			double off = rad * r.range(0.0, 0.35);
			inst.cone(site.x + Math.cos(a) * off, site.z + Math.sin(a) * off, r.range(coneRadius), r.range(coneHeight));
		}
		return inst;
	}

	final class Instance extends Landform {
		final int base;
		final int foot;
		final int crest;
		final double rad;
		final double flankW;
		final double breach;
		final int floorY;
		final int water;
		double ccx = Double.NaN;
		double ccz;
		double ccr;
		double cch;

		Instance(Site s, int base, int foot, int crest, double rad, double flankW, double breach) {
			super(s, maxReach());
			this.base = base;
			this.foot = foot;
			this.crest = crest;
			this.rad = rad;
			this.flankW = flankW;
			this.breach = breach;
			switch (floor) {
				case "sea" -> {
					floorY = Math.min(base, s.sea - 4) - floorDepth;
					water = s.sea - 1;
				}
				case "sand" -> {
					floorY = foot + Math.max(2, (crest - foot) / 4);
					water = Col.NONE;
				}
				default -> {
					int lake = foot + Math.max(3, (crest - foot) / 3);
					floorY = lake - floorDepth;
					water = lake;
				}
			}
			this.holdsWater = water != Col.NONE;
		}

		void cone(double x, double z, double r, double h) {
			ccx = x;
			ccz = z;
			ccr = r;
			cch = h;
		}

		@Override
		public String describe() {
			return NAME + " (" + floor + ") at " + cx + "," + cz + ": r=" + Math.round(rad) + " crest=" + crest + (Double.isNaN(ccx) ? "" : " +cone");
		}

		@Override
		void column(int x, int z, Col c) {
			double px = x + 0.5;
			double pz = z + 0.5;
			double dx = px - cx;
			double dz = pz - cz;
			double a = Math.atan2(dz, dx);
			double d = Math.sqrt(dx * dx + dz * dz);
			double rr = rad * (1 + 0.22 * LNoise.angular(seed ^ 0x71, a, 3) + 0.08 * LNoise.angular(seed ^ 0x72, a, 9)
					+ 0.05 * LNoise.noise2(seed ^ 0x78, px / 14, pz / 14));
			double crestH = crest + (crest - foot) * 0.18 * LNoise.angular(seed ^ 0x73, a, 4);
			double breachK = "sea".equals(floor) ? Math.max(0, 1 - Math.abs(LNoise.angleDiff(a, breach)) / breachWidth) : 0;
			if (breachK > 0) {
				// The breach: the crest drops below the sea so the bay connects to open water.
				crestH = crestH + ((water - 3) - crestH) * Math.min(1, breachK * 1.6);
			}
			double wall = Math.max(3, (crestH - floorY) * 0.35);  // inner wall run (steep)
			double top;
			int kind = K_BODY;
			boolean inside = false;
			if (d < rr - wall) {
				inside = true;
				top = floorY + 0.8 * LNoise.noise2(seed ^ 0x74, px / 9, pz / 9);
				if ("sand".equals(floor)) {
					kind = K_SAND;
				}
			} else if (d < rr) {
				double k = (d - (rr - wall)) / wall;
				top = floorY + (crestH - floorY) * Math.pow(k, 0.6);
				inside = true;
			} else if (d < rr + flankW) {
				double k = (d - rr) / flankW;
				double g = Math.pow(Math.abs(LNoise.angular(seed ^ 0x75, a + 0.2 * LNoise.noise1(seed ^ 0x79, d / 12), 26)), 2);
				double fw = 0.7 + 0.6 * (0.5 + 0.5 * LNoise.angular(seed ^ 0x7A, a, 3));
				k = Math.min(1, (d - rr) / (flankW * fw));
				// The outer flank runs down to the real floor, so a caldera rising out of the sea (rise_from sea_level)
				// slopes on to the sea bed instead of ending in a drowned step at sea level.
				double low = Math.min(foot, base);
				top = low + (crestH - low) * Math.pow(1 - k, 1.4) * (1 - 0.18 * g);
				kind = K_FLANK;
			} else {
				return;
			}
			if (!Double.isNaN(ccx)) {
				double cd = Math.hypot(px - ccx, pz - ccz);
				double ca = Math.atan2(pz - ccz, px - ccx);
				double cw = ccr * (1 + 0.12 * LNoise.angular(seed ^ 0x76, ca, 4));
				if (cd < cw) {
					double t = cd / cw;
					double ch = floorY + (water != Col.NONE ? Math.max(0, water - floorY) : 0) + cch;
					double h = floorY + (ch - floorY) * Math.pow(1 - t, 1.3);
					if (t < 0.18) {
						h -= (0.18 - t) / 0.18 * 4; // small summit crater
					}
					if (h > top) {
						top = h;
						kind = K_CONE;
					}
				}
			}
			int ti = (int) Math.round(top);
			if (inside && ti < base) {
				c.carve = true;
			}
			if (inside && water != Col.NONE && ti < water) {
				c.carve = true;
				c.water = water;
			} else if (!inside && "sea".equals(floor) && ti < water) {
				// Flank below the sea (the breach and the drowned outer slope): keep it open water, never seal it.
				c.water = water;
				c.flag |= Col.FLAG_NO_SEAL;
			}
			c.top = ti;
			c.kind = kind;
		}

		@Override
		BlockState block(int x, int y, int z, int depth, Col c, boolean wet) {
			if (c.kind == K_SAND && depth < 3) {
				return floorBlock.orElse(Blocks.GRAVEL.defaultBlockState());
			}
			if (c.kind == K_FLANK && depth < 3 && !wet) {
				return pick(B.list("tuff", "gravel", "andesite", "basalt", "coarse_dirt"), x + (int) (3 * LNoise.noise2(seed ^ 0x7B, x / 5.0, z / 5.0)), y / 4, z, 0x7C);
			}
			if (c.kind == K_CONE && depth == 0 && !wet) {
				return pick(B.list("tuff", "gravel", "basalt"), x, y, z, 0x77);
			}
			return super.block(x, y, z, depth, c, wet);
		}
	}
}
