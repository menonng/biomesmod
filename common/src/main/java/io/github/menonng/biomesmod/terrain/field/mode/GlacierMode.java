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
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code glacier}: rivers of ice filling the valley floors of mountain terrain (Aletsch, Grey Glacier, Cordillera
 * Darwin). The ice surface is a heavily smoothed copy of the terrain lowered by {@code drop}: wherever the real
 * ground lies below it, the valley fills with ice; ridges and peaks stand clear. Dark medial-moraine stripes run along
 * the ice, and crevasse fields open where it steepens. Above {@code snowline} the ice turns to firn (snow).
 */
public record GlacierMode(Common common, int drop, int blur, double moraineSpacing, int snowline, Crevasses crevasses)
		implements ShaperMode {
	public static final MapCodec<GlacierMode> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Common.MAP_CODEC.forGetter(GlacierMode::common),
			Codec.intRange(0, 120).optionalFieldOf("drop", 6).forGetter(GlacierMode::drop),
			Codec.intRange(1, 8).optionalFieldOf("blur", 6).forGetter(GlacierMode::blur),
			Codec.doubleRange(0, 512).optionalFieldOf("moraine_spacing", 48.0).forGetter(GlacierMode::moraineSpacing),
			Codec.intRange(-64, 320).optionalFieldOf("snowline", 150).forGetter(GlacierMode::snowline),
			Crevasses.CODEC.optionalFieldOf("crevasses", new Crevasses(6, 1.1, 9, 0.3, 90, 14)).forGetter(GlacierMode::crevasses)
	).apply(i, GlacierMode::new));

	private static final BlockState MORAINE = Blocks.GRAVEL.defaultBlockState();

	@Override
	public Type type() {
		return Type.GLACIER;
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
			return WaterPolicy.REPLACE;
		}

		@Override
		public FieldPalette defaultPalette() {
			return P.palette(P.layers(Blocks.PACKED_ICE, 3), P.layers(Blocks.PACKED_ICE, 2),
					P.strata(Blocks.PACKED_ICE, 4, Blocks.BLUE_ICE, 1), 5);
		}

		@Override
		public void prepare(ChunkFrame frame) {
			frame.prepareBase(blur);
		}

		@Override
		public void column(ChunkFrame frame, int x, int z, int i, Column out) {
			double ice = frame.blurBase(x, z) - drop + 1.2 * noise.fbm(x, z, 64, 2);
			int o = frame.orig(i);
			if (o >= ice - 0.5) {
				out.blend = 0; // rock above the ice: leave it
				return;
			}
			double g = ice;
			double crack = crevasses.depthAt(noise, x, z, 0.1);
			if (crack > 0.5) {
				g -= crack;
			}
			out.ground = g;
			out.flags |= Column.FORCE_DRESS;
			if (g >= snowline) {
				out.surface = Blocks.SNOW_BLOCK.defaultBlockState();
				out.surfaceDepth = 2;
			} else if (crack > 0.5) {
				out.surface = Blocks.BLUE_ICE.defaultBlockState();
				out.surfaceDepth = 2;
			} else if (moraineSpacing > 0) {
				// Medial moraines: thin, wavy dark stripes following the direction of the slope.
				double gx = frame.blurBase(x + 8, z) - frame.blurBase(x - 8, z);
				double gz = frame.blurBase(x, z + 8) - frame.blurBase(x, z - 8);
				double len = Math.hypot(gx, gz);
				if (len > 0.5) {
					double across = (-gz * x + gx * z) / len + 6 * noise.fbm(x, z, 90, 2);
					double fr = Math.abs(across / moraineSpacing - Math.floor(across / moraineSpacing + 0.5)) * moraineSpacing;
					if (fr < 1.2 + noise.fbm01(x, z, 25, 1)) {
						out.surface = MORAINE;
						out.surfaceDepth = 1;
					}
				}
			}
		}
	}
}
