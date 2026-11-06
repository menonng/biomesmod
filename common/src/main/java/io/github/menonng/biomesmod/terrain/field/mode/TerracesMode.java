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
 * {@code terraces}: a hillside turned into a staircase of pools.
 * The slope is the regional terrain plus a broad tilt; it is cut into levels {@code step} blocks apart along
 * noise-warped contours (so rims meander like real travertine, never ruled lines). Each level is a pool
 * {@code pool_depth} deep held by a rim one block higher, water filled to just under the rim.
 * <ul>
 * <li>Pamukkale: step 2-3, pool_depth 1, calcite palette, many small lobes ({@code lobe} high).</li>
 * <li>Plitvice: step 6-10, pool_depth 4-7, mossy rims, {@code falls} &gt; 0 drops water over the barriers.</li>
 * </ul>
 */
public record TerracesMode(Common common, int step, int poolDepth, double tilt, double tiltScale, double lobe, double rimWidth,
		double falls, int lift, double poolCoverage, double poolScale, double lobeScale) implements ShaperMode {
	public static final MapCodec<TerracesMode> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Common.MAP_CODEC.forGetter(TerracesMode::common),
			Codec.intRange(1, 24).optionalFieldOf("step", 3).forGetter(TerracesMode::step),
			Codec.intRange(0, 12).optionalFieldOf("pool_depth", 1).forGetter(TerracesMode::poolDepth),
			Codec.doubleRange(0, 200).optionalFieldOf("tilt", 30.0).forGetter(TerracesMode::tilt),
			Codec.doubleRange(32, 4096).optionalFieldOf("tilt_scale", 260.0).forGetter(TerracesMode::tiltScale),
			Codec.doubleRange(0, 4).optionalFieldOf("lobe", 1.0).forGetter(TerracesMode::lobe),
			Codec.doubleRange(0.5, 8).optionalFieldOf("rim_width", 1.3).forGetter(TerracesMode::rimWidth),
			Codec.doubleRange(0, 1).optionalFieldOf("falls", 0.0).forGetter(TerracesMode::falls),
			Codec.intRange(-32, 64).optionalFieldOf("lift", 0).forGetter(TerracesMode::lift),
			Codec.doubleRange(0, 1).optionalFieldOf("pool_coverage", 1.0).forGetter(TerracesMode::poolCoverage),
			Codec.doubleRange(16, 4096).optionalFieldOf("pool_scale", 180.0).forGetter(TerracesMode::poolScale),
			Codec.doubleRange(4, 512).optionalFieldOf("lobe_scale", 40.0).forGetter(TerracesMode::lobeScale)
	).apply(i, TerracesMode::new));

	@Override
	public Type type() {
		return Type.TERRACES;
	}

	@Override
	public Shaper createShaper(long seed) {
		return new S(seed);
	}

	private final class S extends Shaper {
		S(long seed) {
			super(seed);
		}

		/**
		 * Rim meander warp (blocks). The frame's base grid only covers columns with margin + warp <= 16 around the
		 * chunk (bicubic needs one node either side), so the warp is capped and the noise clamped to [-1, 1].
		 */
		private final double warp = Math.min(9.0, lobe * 7);

		@Override
		public int margin() {
			return 3;
		}

		@Override
		public FieldPalette defaultPalette() {
			return P.palette(P.layers(Blocks.CALCITE, 3), P.layers(Blocks.CALCITE, 2),
					P.strata(Blocks.CALCITE, 4, Blocks.DIORITE, 1, Blocks.CALCITE, 3, Blocks.SMOOTH_QUARTZ, 1), 3);
		}

		@Override
		public void prepare(ChunkFrame frame) {
			frame.prepareBase(3);
		}

		/** Continuous slope height (before cutting into steps). Pure. */
		private double slope(ChunkFrame frame, double x, double z) {
			double warpX = x + warp * Noise.clamp(noise.fbm(x, z, lobeScale, 2), -1, 1);
			double warpZ = z + warp * Noise.clamp(noise.fbm(x + 517, z - 233, lobeScale, 2), -1, 1);
			return frame.blurBase((int) Math.floor(warpX), (int) Math.floor(warpZ)) + lift
					+ tilt * noise.fbm01(warpX, warpZ, tiltScale, 2);
		}

		private final double poolT = io.github.menonng.biomesmod.terrain.field.Coverage.threshold(poolCoverage, 3);

		@Override
		public void column(ChunkFrame frame, int x, int z, int i, Column out) {
			if (poolCoverage < 1) {
				// Terraced pools only where the mask says so (a chain of lakes); elsewhere the slope stays natural.
				double m = Noise.smoothstep(poolT - 0.05, poolT + 0.12, noise.fbm(x - 1931, z + 4421, poolScale, 3));
				if (m <= 0) {
					out.blend = 0;
					return;
				}
				out.blend = m;
			}
			double s = slope(frame, x, z);
			double k = Math.floor(s / step);
			double level = k * step;           // pool rim top of this terrace
			double into = s - level;           // 0 at the outer (lower) edge, step at the inner edge
			// The rim is the band just inside the outer edge of each step.
			boolean rim = into < rimWidth;
			int rimTop = (int) level;
			if (rim || poolDepth == 0) {
				out.ground = rimTop;
			} else {
				out.ground = rimTop - poolDepth;
				out.water = rimTop + 1;          // water surface level with the rim top
			}
			if (falls > 0 && rim) {
				// Water spilling over the barrier: on the lower side of the rim, sometimes.
				double spot = noise.fbm(x * 0.7, z * 0.7, 9, 2);
				if (spot > 1.0 - falls * 1.6 && into < 0.6) {
					out.ground = rimTop - step;
					out.flags |= Column.FALL | Column.NO_WALL;
					out.fallTop = rimTop + (poolDepth > 0 ? 0 : 1);
				}
			}
		}
	}
}
