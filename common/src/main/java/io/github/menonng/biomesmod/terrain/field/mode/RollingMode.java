package io.github.menonng.biomesmod.terrain.field.mode;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.menonng.biomesmod.terrain.field.ChunkFrame;
import io.github.menonng.biomesmod.terrain.field.Column;
import io.github.menonng.biomesmod.terrain.field.Common;
import io.github.menonng.biomesmod.terrain.field.Noise;
import io.github.menonng.biomesmod.terrain.field.Shaper;
import io.github.menonng.biomesmod.terrain.field.ShaperMode;

/**
 * {@code rolling}: smooths broken terrain into wide, gently rolling ground (Mongolian steppe, Patagonian meseta).
 * {@code smooth} pulls the surface towards the regional height, {@code hills} adds long low swells, and
 * {@code mesa_step} &gt; 0 cuts the land into broad flat tablelands with short escarpments (Patagonian mesetas).
 * {@code lift} raises the land; with {@code lift_ramp} 0 it rises straight from the shore as a cliff (Moher), with
 * {@code lift_ramp} N the lift grows over the first N blocks of original height above the sea (a plateau that slopes
 * up from the coast, Puna).
 */
public record RollingMode(Common common, double smooth, double hills, double hillScale, int lift, int mesaStep, int liftRamp) implements ShaperMode {
	public static final MapCodec<RollingMode> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Common.MAP_CODEC.forGetter(RollingMode::common),
			Codec.doubleRange(0, 1).optionalFieldOf("smooth", 0.8).forGetter(RollingMode::smooth),
			Codec.doubleRange(0, 40).optionalFieldOf("hills", 5.0).forGetter(RollingMode::hills),
			Codec.doubleRange(16, 4096).optionalFieldOf("hill_scale", 180.0).forGetter(RollingMode::hillScale),
			Codec.intRange(-32, 64).optionalFieldOf("lift", 0).forGetter(RollingMode::lift),
			Codec.intRange(0, 40).optionalFieldOf("mesa_step", 0).forGetter(RollingMode::mesaStep),
			Codec.intRange(0, 64).optionalFieldOf("lift_ramp", 0).forGetter(RollingMode::liftRamp)
	).apply(i, RollingMode::new));

	@Override
	public Type type() {
		return Type.ROLLING;
	}

	@Override
	public Shaper createShaper(long seed) {
		return new S(seed);
	}

	private final class S extends Shaper {
		S(long seed) {
			super(seed);
		}

		@Override
		public boolean fullSurface() {
			return false;
		}

		@Override
		public WaterPolicy waterPolicy() {
			return WaterPolicy.KEEP;
		}

		@Override
		public void prepare(ChunkFrame frame) {
			frame.prepareBase(5);
		}

		@Override
		public void column(ChunkFrame frame, int x, int z, int i, Column out) {
			double regional = frame.blurBase(x, z);
			double lifted = liftRamp > 0 ? lift * Noise.smoothstep(frame.seaLevel, frame.seaLevel + liftRamp, frame.orig(i)) : lift;
			double g = Noise.lerp(frame.orig(i), regional, smooth) + lifted + hills * noise.fbm(x, z, hillScale, 3);
			if (mesaStep > 0) {
				double warped = g + 3 * noise.fbm(x, z, 40, 2);
				double k = Math.floor(warped / mesaStep);
				double fr = warped / mesaStep - k;
				g = (k + Noise.smoothstep(0.82, 1.0, fr)) * mesaStep;   // flat tread, short riser
			}
			out.ground = Math.max(g, frame.seaLevel + 1.0);
			if (frame.orig(i) < frame.seaLevel && regional < frame.seaLevel) {
				out.blend = 0; // never fill the sea or large lakes
			}
			// A narrow river or pond in land (regional surface above the sea) is lifted with the land around it;
			// keeping it would leave a one-block slot canyon as deep as the lift.
		}
	}
}
