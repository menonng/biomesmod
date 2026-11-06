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
 * {@code ice_sheet}: an ice cap that buries the land under a smooth dome of ice (Vostok plateau, Franz Josef Land
 * caps), and, over the sea, an ice shelf floating on the original water with a sheer marine cliff where it meets
 * open water (Ross Ice Shelf). Crevasse fields open on steeper ice and near the margins.
 *
 * @param thickness  ice above the land surface in the interior
 * @param dome       extra height in the interior (rises over {@code dome_scale} blocks from the edge)
 * @param shelf      true: also cover water with a floating shelf
 * @param freeboard  shelf top above sea level
 * @param crevasses  crevasse field settings (coverage 0 = none)
 */
public record IceSheetMode(Common common, int thickness, int dome, double domeScale, boolean shelf, int freeboard, double roughness,
		Crevasses crevasses) implements ShaperMode {
	public static final MapCodec<IceSheetMode> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Common.MAP_CODEC.forGetter(IceSheetMode::common),
			Codec.intRange(0, 160).optionalFieldOf("thickness", 14).forGetter(IceSheetMode::thickness),
			Codec.intRange(0, 160).optionalFieldOf("dome", 20).forGetter(IceSheetMode::dome),
			Codec.doubleRange(16, 4096).optionalFieldOf("dome_scale", 300.0).forGetter(IceSheetMode::domeScale),
			Codec.BOOL.optionalFieldOf("shelf", false).forGetter(IceSheetMode::shelf),
			Codec.intRange(1, 60).optionalFieldOf("freeboard", 12).forGetter(IceSheetMode::freeboard),
			Codec.doubleRange(0, 8).optionalFieldOf("roughness", 1.2).forGetter(IceSheetMode::roughness),
			Crevasses.CODEC.optionalFieldOf("crevasses", new Crevasses(8, 1.3, 10, 0.1, 160, 30)).forGetter(IceSheetMode::crevasses)
	).apply(i, IceSheetMode::new));

	@Override
	public Type type() {
		return Type.ICE_SHEET;
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
		public int edgeReach() {
			return (int) Math.min(240, domeScale);
		}

		@Override
		public WaterPolicy waterPolicy() {
			return WaterPolicy.KEEP;
		}

		@Override
		public int defaultCliffSlope() {
			return 4;
		}

		@Override
		public FieldPalette defaultPalette() {
			return P.palette(P.layers(Blocks.SNOW_BLOCK, 2, Blocks.PACKED_ICE, 3), P.layers(Blocks.PACKED_ICE, 2),
					P.strata(Blocks.PACKED_ICE, 5, Blocks.BLUE_ICE, 1, Blocks.PACKED_ICE, 3), 4);
		}

		@Override
		public void prepare(ChunkFrame frame) {
			frame.prepareBase(6);
		}

		@Override
		public void column(ChunkFrame frame, int x, int z, int i, Column out) {
			int sea = frame.seaLevel;
			double inner = Noise.smoothstep(0, domeScale, frame.edgeDistance(i));
			double rough = roughness * noise.fbm(x, z, 48, 3);
			boolean water = frame.origWater(i) != ChunkFrame.NONE && frame.orig(i) < sea - 1;
			double g;
			if (water) {
				if (!shelf) {
					return;
				}
				g = sea + freeboard + dome * 0.25 * inner + rough * 0.5;
				out.keepWaterBelow = sea;  // the shelf floats: original sea water stays under it
				// Marine cliff: full height right from the edge instead of a ramp.
				out.blend = frame.edgeDistance(i) > 1.5 + 2 * noise.fbm01(x, z, 30, 1) ? 1 : 0;
			} else {
				double surface = Math.max(frame.blurBase(x, z), frame.orig(i) - 4);
				g = surface + thickness + dome * inner + rough;
			}
			double crack = crevasses.depthAt(noise, x, z, 0.25 * (1 - inner));
			if (crack > 0.5) {
				g -= crack;
				out.surface = Blocks.BLUE_ICE.defaultBlockState();
				out.surfaceDepth = 2;
			}
			out.ground = g;
		}
	}
}
