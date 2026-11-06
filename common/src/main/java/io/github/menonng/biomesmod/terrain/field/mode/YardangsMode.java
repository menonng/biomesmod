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
 * {@code yardangs}: wind-carved ridges (the kaluts of Dasht-e Lut): long, streamlined, steep-sided ridges all aligned
 * with the wind, with blunt upwind heads and tapering tails, separated by flat sandy corridors.
 *
 * @param height     max ridge height above the corridor floor
 * @param spacing    corridor-to-corridor distance across the wind
 * @param length     typical ridge length along the wind
 * @param width      ridge share of the spacing (0..1)
 */
public record YardangsMode(Common common, double height, double spacing, double length, double width, double direction,
		int baseOffset) implements ShaperMode {
	public static final MapCodec<YardangsMode> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Common.MAP_CODEC.forGetter(YardangsMode::common),
			Codec.doubleRange(2, 120).optionalFieldOf("height", 22.0).forGetter(YardangsMode::height),
			Codec.doubleRange(8, 512).optionalFieldOf("spacing", 34.0).forGetter(YardangsMode::spacing),
			Codec.doubleRange(16, 2048).optionalFieldOf("length", 160.0).forGetter(YardangsMode::length),
			Codec.doubleRange(0.1, 0.9).optionalFieldOf("width", 0.42).forGetter(YardangsMode::width),
			Codec.doubleRange(-360, 360).optionalFieldOf("direction", -20.0).forGetter(YardangsMode::direction),
			Codec.intRange(-32, 64).optionalFieldOf("base_offset", 0).forGetter(YardangsMode::baseOffset)
	).apply(i, YardangsMode::new));

	@Override
	public Type type() {
		return Type.YARDANGS;
	}

	@Override
	public Shaper createShaper(long seed) {
		return new S(seed);
	}

	private final class S extends Shaper {
		private final double cos = Math.cos(Math.toRadians(direction));
		private final double sin = Math.sin(Math.toRadians(direction));

		S(long seed) {
			super(seed);
		}

		@Override
		public int defaultCliffSlope() {
			return 3;
		}

		@Override
		public FieldPalette defaultPalette() {
			return P.palette(P.layers(Blocks.SAND, 2), P.layers(Blocks.SAND, 2),
					P.strata(Blocks.SANDSTONE, 2, Blocks.TERRACOTTA, 3, Blocks.SMOOTH_SANDSTONE, 2, "brown_terracotta", 1), 3);
		}

		@Override
		public void prepare(ChunkFrame frame) {
			frame.prepareBase(6);
		}

		@Override
		public void column(ChunkFrame frame, int x, int z, int i, Column out) {
			double base = Math.max(frame.seaLevel + 1, Math.floor(frame.blurBase(x, z) + 0.5)) + baseOffset;
			double u = x * cos + z * sin;                 // along the wind
			double v = -x * sin + z * cos + 0.45 * spacing * noise.fbm(u, x * 0.3, length * 1.6, 2);
			double lane = Math.floor(v / spacing);
			double fv = v / spacing - lane;               // 0..1 across the lane
			double halfW = width * 0.5 * (0.55 + 0.9 * noise.cell01((int) lane, 13)) * (0.8 + 0.4 * noise.fbm01(x, z, 22, 2));
			if (noise.cell01((int) lane, 17) < 0.18) {
				halfW = 0; // some lanes are open corridors
			}
			double cross = Math.abs(fv - 0.5);
			double h = 0;
			if (cross < halfW) {
				// Each lane holds a chain of ridges; a ridge runs from a blunt head to a tapering tail.
				double segLen = length * (0.7 + 0.6 * noise.cell01((int) lane, 29));
				double su = u / segLen + noise.cell01((int) lane, 31);
				double seg = Math.floor(su);
				double t = su - seg;                       // 0 = upwind head, 1 = downwind tail
				double gap = 0.12 + 0.12 * noise.cell01((int) lane * 7 + (int) seg, 37);
				if (t > gap) {
					double tt = (t - gap) / (1 - gap);
					double along = Math.min(1, tt * 7) * Math.pow(1 - tt, 0.55);   // blunt head, long taper
					double profile = 1 - Math.pow(cross / halfW, 3.0);            // steep sides, rounded top
					double hs = height * (0.55 + 0.45 * noise.cell01((int) lane * 13 + (int) seg, 41));
					h = hs * along * profile;
				}
			}
			out.ground = base + h;
		}
	}
}
