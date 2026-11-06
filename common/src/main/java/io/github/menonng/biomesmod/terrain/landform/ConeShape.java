package io.github.menonng.biomesmod.terrain.landform;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.menonng.biomesmod.terrain.util.StrataPalette;
import java.util.Optional;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code cone}: a stratovolcano with concave flanks cut by radial gullies, a summit crater (dry, lava-filled or a
 * crater lake) and an optional snow cap. With {@code rise_from: sea_level} it stands out of the sea from the sea floor
 * (Alaid / Atlasov Island); on land it is a smaller scoria cone (Batok). The base outline and crest are irregular.
 *
 * @param crater   "dry", "lava" or "lake"
 * @param snowline absolute Y above which the flanks are snow-covered (or none)
 */
public final class ConeShape extends LandformShape {
	static final String NAME = "cone";

	static final MapCodec<ConeShape> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Span.CODEC.optionalFieldOf("radius", Span.of(60, 95)).forGetter(s -> s.radius),
			Span.CODEC.optionalFieldOf("height", Span.of(70, 120)).forGetter(s -> s.height),
			Codec.floatRange(0.6f, 3).optionalFieldOf("concavity", 1.55f).forGetter(s -> s.concavity),
			Span.CODEC.optionalFieldOf("crater_radius", Span.of(7, 12)).forGetter(s -> s.craterRadius),
			Span.CODEC.optionalFieldOf("crater_depth", Span.of(8, 14)).forGetter(s -> s.craterDepth),
			Codec.STRING.optionalFieldOf("crater", "dry").forGetter(s -> s.crater),
			Codec.floatRange(0, 1).optionalFieldOf("gullies", 0.5f).forGetter(s -> s.gullies),
			Codec.INT.optionalFieldOf("snowline").forGetter(s -> s.snowline)
	).apply(i, ConeShape::new));

	static final int K_SNOW = 20;
	static final java.util.List<BlockState> FLANK = B.list("tuff", "gravel", "basalt", "andesite", "tuff", "coarse_dirt");

	final Span radius;
	final Span height;
	final float concavity;
	final Span craterRadius;
	final Span craterDepth;
	final String crater;
	final float gullies;
	final Optional<Integer> snowline;

	ConeShape(Span radius, Span height, float concavity, Span craterRadius, Span craterDepth, String crater, float gullies,
			Optional<Integer> snowline) {
		this.radius = radius;
		this.height = height;
		this.concavity = concavity;
		this.craterRadius = craterRadius;
		this.craterDepth = craterDepth;
		this.crater = crater;
		this.gullies = gullies;
		this.snowline = snowline;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	int maxReach() {
		return (int) Math.ceil(radius.max() * 1.25 + 4);
	}

	@Override
	StrataPalette defaultPalette() {
		return B.strata(null, 0, "basalt", 3, "tuff", 2, "blackstone", 1, "andesite", 2, "tuff", 2);
	}

	@Override
	Span defaultBaseRange() {
		return Span.of(-80, 200);
	}

	@Override
	int defaultMaxRelief() {
		return 400; // volcanoes bury whatever is there
	}

	@Override
	Landform create(Site site) {
		LNoise.Rng r = site.rng;
		double rad = r.range(radius);
		int floor = Site.min(site.ring(rad * 0.5, 6), site.floor0);
		int foot = site.riseBase(floor);
		int summit = (int) Math.min(foot + r.range(height), site.ceiling());
		return new Instance(site, floor, summit, rad, r.range(craterRadius), r.range(craterDepth), r.angle());
	}

	final class Instance extends Landform {
		final int floor;
		final int summit;
		final double rad;
		final double cr;
		final double cd;
		final double breach;
		final int lakeY;

		Instance(Site s, int floor, int summit, double rad, double cr, double cd, double breach) {
			super(s, maxReach());
			this.floor = floor;
			this.summit = summit;
			this.rad = rad;
			this.cr = cr;
			this.cd = cd;
			this.breach = breach;
			this.lakeY = summit - (int) Math.round(cd * 0.55);
			this.holdsWater = !"dry".equals(crater);
		}

		@Override
		public String describe() {
			return NAME + " at " + cx + "," + cz + ": r=" + Math.round(rad) + " summit=" + summit + " floor=" + floor;
		}

		@Override
		void column(int x, int z, Col c) {
			double px = x + 0.5;
			double pz = z + 0.5;
			double dx = px - cx;
			double dz = pz - cz;
			double a = Math.atan2(dz, dx);
			// Irregular footprint and lumpy flanks.
			double warp = 1 + 0.16 * LNoise.angular(seed ^ 0x61, a, 4) + 0.06 * LNoise.angular(seed ^ 0x62, a, 11);
			double d = Math.sqrt(dx * dx + dz * dz) / warp;
			if (d >= rad * 1.2) {
				return;
			}
			double t = Math.min(1, d / rad);
			double prof = Math.pow(1 - t, concavity);
			// Radial gullies, deeper low on the flank, and a gentle skirt beyond the nominal radius.
			double g = gullies * Math.pow(Math.abs(LNoise.angular(seed ^ 0x63, a + 0.25 * LNoise.noise1(seed ^ 0x64, d / 14), 30)), 2);
			double h = (summit - floor) * prof * (1 - 0.22 * g * Math.sqrt(t));
			if (d > rad) {
				h = (summit - floor) * 0.02 * (1 - (d - rad) / (rad * 0.2));
			}
			h += 1.0 * LNoise.noise2(seed ^ 0x65, px / 7, pz / 7) * t;
			int top = floor + (int) Math.round(Math.max(0, h));
			double rimR = cr * (1 + 0.12 * LNoise.angular(seed ^ 0x66, a, 5));
			double rawD = Math.sqrt(dx * dx + dz * dz);
			if (rawD < rimR * 1.35) {
				// Crater: bowl inside the rim, rim crest slightly raised, a low notch on one side.
				double k = rawD / rimR;
				double notch = Math.max(0, 1 - Math.abs(LNoise.angleDiff(a, breach)) / 0.5) * 2.5;
				int rim = summit + 1 - (int) Math.round(notch);
				if (k < 1) {
					top = (int) Math.round(summit - cd * (1 - k * k));
					c.carve = true;
					if (!"dry".equals(crater) && top < lakeY) {
						c.water = lakeY;
						if ("lava".equals(crater)) {
							c.fluid = Blocks.LAVA.defaultBlockState();
						}
					}
				} else {
					top = Math.max(top, rim - (int) Math.round((k - 1) * 3));
				}
			}
			if (top <= floor && d > rad) {
				return;
			}
			c.top = top;
			c.kind = snowline.isPresent() && top >= snowline.get() + (int) Math.round(3 * LNoise.noise2(seed ^ 0x67, px / 11, pz / 11))
					? K_SNOW : K_BODY;
		}

		@Override
		BlockState block(int x, int y, int z, int depth, Col c, boolean wet) {
			if (c.kind == K_SNOW && depth <= 1 && !wet) {
				return depth == 0 ? Blocks.SNOW_BLOCK.defaultBlockState() : Blocks.PACKED_ICE.defaultBlockState();
			}
			if (depth < 3 && !wet && !c.carve) {
				// Loose scoria and ash streaked down the slope: no horizontal bands on a smooth cone.
				return pick(FLANK, x + (int) (3 * LNoise.noise2(seed ^ 0x68, x / 5.0, z / 5.0)), y / 4, z, 0x69);
			}
			return super.block(x, y, z, depth, c, wet);
		}
	}
}
