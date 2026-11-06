package io.github.menonng.biomesmod.terrain.field.mode;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.menonng.biomesmod.terrain.field.ChunkFrame;
import io.github.menonng.biomesmod.terrain.field.Column;
import io.github.menonng.biomesmod.terrain.field.Common;
import io.github.menonng.biomesmod.terrain.field.Shaper;
import io.github.menonng.biomesmod.terrain.field.ShaperMode;

/**
 * {@code depression}: ground lowered below sea level and kept dry (polders, basins). The biome edge becomes a dike:
 * an embankment {@code dike_height} above sea level whose crest wanders with noise and follows the biome outline,
 * so no water can reach the dry floor. Inside, the floor sits {@code depth} blocks under sea level with gentle relief
 * and optional drainage ditches (water held well below the surrounding ground).
 *
 * @param depth        how far the floor sits under sea level (blocks)
 * @param relief       floor undulation amplitude (blocks)
 * @param dikeHeight   crest height above sea level; 0 = no dike (only safe far from water)
 * @param dikeWidth    crest-to-toe half width in blocks
 * @param ditchSpacing blocks between drainage ditches (0 = none); ditches follow gently curving lines
 * @param ditchDepth   ditch depth below the floor; its water stays 1 block below the floor
 */
public record DepressionMode(Common common, int depth, double relief, int dikeHeight, int dikeWidth, double ditchSpacing,
		int ditchDepth) implements ShaperMode {
	public static final MapCodec<DepressionMode> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Common.MAP_CODEC.forGetter(DepressionMode::common),
			Codec.intRange(1, 40).optionalFieldOf("depth", 6).forGetter(DepressionMode::depth),
			Codec.doubleRange(0, 8).optionalFieldOf("relief", 1.2).forGetter(DepressionMode::relief),
			Codec.intRange(0, 16).optionalFieldOf("dike_height", 2).forGetter(DepressionMode::dikeHeight),
			Codec.intRange(2, 16).optionalFieldOf("dike_width", 4).forGetter(DepressionMode::dikeWidth),
			Codec.doubleRange(0, 512).optionalFieldOf("ditch_spacing", 0.0).forGetter(DepressionMode::ditchSpacing),
			Codec.intRange(1, 6).optionalFieldOf("ditch_depth", 2).forGetter(DepressionMode::ditchDepth)
	).apply(i, DepressionMode::new));

	@Override
	public Type type() {
		return Type.DEPRESSION;
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
		 * The crest sits where the edge factor is guaranteed >= ChunkFrame.WET_F (0.5) whatever the edge jitter,
		 * because below that the frame keeps original water: inside the crest everything must be dry.
		 */
		private int crestAt() {
			return Math.max(dikeWidth + 1, (int) Math.ceil((common.edgeNoise() + 0.5) * common.edgeBlend()) + 1);
		}

		@Override
		public int edgeReach() {
			return crestAt() + dikeWidth * 3 + 8;
		}

		@Override
		public boolean fullSurface() {
			return false; // keep the meadow / forest floor blocks the column already had
		}

		@Override
		public void column(ChunkFrame frame, int x, int z, int i, Column out) {
			int sea = frame.seaLevel;
			double floor = sea - depth + relief * noise.fbm(x, z, 96, 2);
			double d = frame.edgeDistance(i);
			int crestAt = crestAt();
			if (dikeHeight > 0) {
				// Full reshaping right behind the dike so the edge fade never leaves sea water inside.
				out.blend = d >= crestAt ? 1.0 : 0.0;
				double crest = sea + dikeHeight + 0.6 * noise.fbm(x, z, 40, 1);
				double t = Math.abs(d - crestAt) / dikeWidth;
				if (t < 1) {
					double h = crest - (crest - floor) * t * t; // rounded embankment profile
					out.minGround = Math.max(sea + 0.0, h);
					out.ground = Math.max(floor, h);
					return;
				}
			}
			out.ground = floor;
			if (ditchSpacing > 0 && d > crestAt + dikeWidth + 2) {
				double u = (x + 9 * noise.simplex(x / 180.0, z / 180.0)) / ditchSpacing;
				double fu = Math.abs(u - Math.floor(u + 0.5)) * ditchSpacing;
				if (fu < 0.8) {
					int floorY = (int) Math.floor(floor + 0.5);
					out.ground = floorY - ditchDepth;
					out.water = floorY; // water surface one block under the surrounding floor
				}
			}
		}
	}
}
