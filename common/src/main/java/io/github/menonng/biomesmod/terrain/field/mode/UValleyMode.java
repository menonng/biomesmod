package io.github.menonng.biomesmod.terrain.field.mode;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.menonng.biomesmod.terrain.field.ChunkFrame;
import io.github.menonng.biomesmod.terrain.field.Column;
import io.github.menonng.biomesmod.terrain.field.Common;
import io.github.menonng.biomesmod.terrain.field.FieldPalette;
import io.github.menonng.biomesmod.terrain.field.Noise;
import io.github.menonng.biomesmod.terrain.field.Shaper;
import io.github.menonng.biomesmod.terrain.field.ShaperMode;
import net.minecraft.world.level.block.Blocks;

/**
 * {@code u_valley}: a glacial trough. Flat floor, near-vertical walls, high shoulders, and waterfalls that drop from
 * the rim (Lauterbrunnen's 72 falls). With {@code flood_to_sea} the floor sinks below sea level and fills to sea
 * level: a fjord (Geirangerfjord, Seven Sisters falling straight into the water).
 *
 * @param shoulder   rim height above the regional terrain
 * @param floorDepth floor below the regional terrain (dry valley) or below sea level (fjord)
 * @param width      rim-to-rim width
 * @param floorWidth flat floor width
 * @param steepness  wall exponent: higher = more vertical (2 = U, 6 = cliffs)
 * @param falls      waterfalls per 100 blocks of wall (0 = none)
 */
public record UValleyMode(Common common, int shoulder, int floorDepth, double width, double floorWidth, double steepness,
		double direction, double spacing, double falls, boolean floodToSea) implements ShaperMode {
	public static final MapCodec<UValleyMode> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Common.MAP_CODEC.forGetter(UValleyMode::common),
			Codec.intRange(0, 160).optionalFieldOf("shoulder", 50).forGetter(UValleyMode::shoulder),
			Codec.intRange(0, 120).optionalFieldOf("floor_depth", 10).forGetter(UValleyMode::floorDepth),
			Codec.doubleRange(24, 800).optionalFieldOf("width", 110.0).forGetter(UValleyMode::width),
			Codec.doubleRange(4, 400).optionalFieldOf("floor_width", 40.0).forGetter(UValleyMode::floorWidth),
			Codec.doubleRange(1, 12).optionalFieldOf("steepness", 5.0).forGetter(UValleyMode::steepness),
			Codec.doubleRange(-360, 360).optionalFieldOf("direction", 0.0).forGetter(UValleyMode::direction),
			Codec.doubleRange(128, 4096).optionalFieldOf("spacing", 600.0).forGetter(UValleyMode::spacing),
			Codec.doubleRange(0, 20).optionalFieldOf("falls", 1.0).forGetter(UValleyMode::falls),
			Codec.BOOL.optionalFieldOf("flood_to_sea", false).forGetter(UValleyMode::floodToSea)
	).apply(i, UValleyMode::new));

	@Override
	public Type type() {
		return Type.U_VALLEY;
	}

	@Override
	public Shaper createShaper(long seed) {
		return new S(seed);
	}

	private final class S extends Shaper {
		private final Meander line;
		private final double[] tmp = new double[3];

		S(long seed) {
			super(seed);
			// Glacial troughs are straighter than rivers: small meander amplitude, long wavelength.
			line = new Meander(noise.fork(3), direction, spacing, spacing * 0.2, width * 3.2);
		}

		@Override
		public int defaultCliffSlope() {
			return 4;
		}

		@Override
		public boolean fullSurface() {
			return false;
		}

		@Override
		public WaterPolicy waterPolicy() {
			return floodToSea ? WaterPolicy.REPLACE : WaterPolicy.KEEP;
		}

		@Override
		public FieldPalette defaultPalette() {
			return P.palette(null, P.layers(Blocks.GRAVEL, 2), P.strata(Blocks.STONE, 6, Blocks.ANDESITE, 2, Blocks.STONE, 4,
					Blocks.TUFF, 1), 4);
		}

		@Override
		public void prepare(ChunkFrame frame) {
			frame.prepareBase(5);
		}

		@Override
		public void column(ChunkFrame frame, int x, int z, int i, Column out) {
			double regional = frame.blurBase(x, z);
			double rim = regional + shoulder + 0.5 * (frame.orig(i) - regional);
			double floorY = floodToSea ? frame.seaLevel - floorDepth : regional - floorDepth;
			line.eval(x, z, tmp);
			double d = Math.abs(tmp[0]);
			double half = width * 0.5 * (0.6 + 0.8 * noise.fbm01(x, z, 220, 2));
			double fh = Math.min(half - 4, floorWidth * 0.5);
			double g;
			if (d <= fh) {
				g = floorY + 0.8 * noise.fbm(x, z, 70, 1);
			} else if (d < half) {
				double t = (d - fh) / (half - fh);
				// Steep U: the wall rises almost vertically, with a little curvature at the toe and the lip.
				double u = Math.pow(Noise.smoothstep(0, 1, t), 1.0 / steepness);
				u = Math.min(1, u + 0.04 * noise.fbm(x, z, 18, 2));
				g = floorY + (rim - floorY) * u;
			} else {
				g = rim;
			}
			out.ground = g;
			if (floodToSea && d < half) {
				out.water = frame.seaLevel;
			}
			// Waterfalls: columns at the wall foot carry a fall from the rim, at intervals along the valley.
			if (falls > 0 && d > fh - 1 && d < fh + 2.5) {
				double along = tmp[1];
				double side = Math.signum(tmp[0]);
				double cell = 100.0 / falls;
				double k = Math.floor(along / cell);
				double at = (k + 0.2 + 0.6 * noise.cell01((int) k, side > 0 ? 7 : 11)) * cell;
				if (Math.abs(along - at) < 0.6 && noise.cell01((int) k, side > 0 ? 3 : 5) > 0.35) {
					int rimY = (int) Math.floor(rim);
					out.flags |= Column.FALL | Column.NO_WALL;
					out.fallTop = rimY - 1;
					out.ground = Math.min(g, floorY);
				}
			}
		}
	}
}
