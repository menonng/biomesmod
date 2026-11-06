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
 * {@code canyon}: a plateau cut by a deep, meandering canyon with stepped walls (alternating cliffs and talus
 * benches, like the Grand Canyon's rock layers), shorter side canyons that deepen towards the main one, and a river
 * on the floor. Centre lines come from {@link Meander}: they wander but never loop back into rings or crescents.
 *
 * @param plateau     plateau height above the regional terrain
 * @param depth       main canyon depth below the plateau
 * @param width       main canyon rim-to-rim width
 * @param floorWidth  flat floor width
 * @param benches     number of cliff/bench steps in each wall
 * @param spacing     distance between parallel main canyons
 * @param sideSpacing distance between side canyons (0 = none)
 * @param riverWidth  river width on the floor (0 = dry)
 */
public record CanyonMode(Common common, int plateau, int depth, double width, double floorWidth, int benches, double direction,
		double spacing, double sideSpacing, double riverWidth) implements ShaperMode {
	public static final MapCodec<CanyonMode> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Common.MAP_CODEC.forGetter(CanyonMode::common),
			Codec.intRange(0, 120).optionalFieldOf("plateau", 18).forGetter(CanyonMode::plateau),
			Codec.intRange(8, 200).optionalFieldOf("depth", 70).forGetter(CanyonMode::depth),
			Codec.doubleRange(16, 600).optionalFieldOf("width", 140.0).forGetter(CanyonMode::width),
			Codec.doubleRange(2, 200).optionalFieldOf("floor_width", 14.0).forGetter(CanyonMode::floorWidth),
			Codec.intRange(1, 12).optionalFieldOf("benches", 5).forGetter(CanyonMode::benches),
			Codec.doubleRange(-360, 360).optionalFieldOf("direction", 35.0).forGetter(CanyonMode::direction),
			Codec.doubleRange(128, 4096).optionalFieldOf("spacing", 520.0).forGetter(CanyonMode::spacing),
			Codec.doubleRange(0, 2048).optionalFieldOf("side_spacing", 150.0).forGetter(CanyonMode::sideSpacing),
			Codec.doubleRange(0, 24).optionalFieldOf("river_width", 4.0).forGetter(CanyonMode::riverWidth)
	).apply(i, CanyonMode::new));

	@Override
	public Type type() {
		return Type.CANYON;
	}

	@Override
	public Shaper createShaper(long seed) {
		return new S(seed);
	}

	private final class S extends Shaper {
		private final Meander main;
		private final Meander side;
		private final double[] tmp = new double[3];

		S(long seed) {
			super(seed);
			main = new Meander(noise.fork(1), direction, spacing, spacing * 0.18, width * 3.5);
			side = sideSpacing > 0 ? new Meander(noise.fork(2), direction + 90, sideSpacing, sideSpacing * 0.18, width * 1.6) : null;
		}

		@Override
		public boolean fullSurface() {
			return false; // the plateau keeps its own surface; only cut columns get the palette
		}

		@Override
		public int defaultCliffSlope() {
			return 3;
		}

		@Override
		public FieldPalette defaultPalette() {
			return P.palette(null, P.layers(Blocks.SAND, 1, Blocks.RED_SANDSTONE, 2),
					P.strata("red_terracotta", 5, "orange_terracotta", 3, Blocks.TERRACOTTA, 4, "white_terracotta", 2,
							"brown_terracotta", 4, Blocks.RED_SANDSTONE, 3, "light_gray_terracotta", 2), 3);
		}

		@Override
		public void prepare(ChunkFrame frame) {
			frame.prepareBase(6);
		}

		/** Stepped wall profile: 0 at the floor edge, 1 at the rim, cliffs separated by gently sloping benches. */
		private double wall(double t, double x, double z) {
			t = Noise.clamp(t, 0, 1);
			double steps = benches;
			double k = Math.floor(t * steps);
			double fr = t * steps - k;
			double cliff = 0.35 + 0.15 * noise.fbm(x, z, 60, 1); // share of each step that is cliff
			double within = fr < cliff ? 0.15 * fr / cliff : 0.15 + 0.85 * Noise.smoothstep(cliff, 1, fr);
			return (k + within) / steps;
		}

		@Override
		public void column(ChunkFrame frame, int x, int z, int i, Column out) {
			double top = frame.blurBase(x, z) + plateau;
			double floorY = top - depth * (0.85 + 0.15 * noise.fbm01(x, z, 400, 1));
			main.eval(x, z, tmp);
			double half = width * 0.5 * (0.8 + 0.4 * noise.fbm01(x, z, 300, 2));
			double fh = floorWidth * 0.5;
			double dMain = Math.abs(tmp[0]);
			double g = top;
			if (dMain < half) {
				double t = (dMain - fh) / (half - fh);
				g = floorY + (top - floorY) * wall(t, x, z);
			}
			if (side != null) {
				side.eval(x, z, tmp);
				double sd = Math.abs(tmp[0]);
				// Side canyons fade out away from the main one: depth falls with distance from it.
				double reachT = Noise.smoothstep(spacing * 0.42, half, dMain);
				double sideDepth = depth * 0.7 * (1 - reachT);
				double sideHalf = width * 0.22;
				if (sideDepth > 2 && sd < sideHalf) {
					double sideFloor = top - sideDepth;
					double t = sd / sideHalf;
					g = Math.min(g, sideFloor + (top - sideFloor) * wall(t, x + 91, z - 37));
				}
			}
			// The plateau is the regional surface lifted by `plateau` (faded in at the biome edge by the frame),
			// with a little of the original relief kept so it is not a table.
			if (g >= top - 0.01) {
				g = top + 0.35 * (frame.orig(i) - frame.blurBase(x, z));
			}
			out.ground = g;
			if (riverWidth > 0 && dMain < riverWidth * 0.5 + 0.5) {
				int f = (int) Math.floor(floorY + 0.5);
				out.ground = f - 2;
				out.water = f;
			}
		}
	}
}
