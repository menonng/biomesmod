package io.github.menonng.biomesmod.terrain.field.mode;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.menonng.biomesmod.terrain.field.ChunkFrame;
import io.github.menonng.biomesmod.terrain.field.Column;
import io.github.menonng.biomesmod.terrain.field.Common;
import io.github.menonng.biomesmod.terrain.field.Coverage;
import io.github.menonng.biomesmod.terrain.field.FieldPalette;
import io.github.menonng.biomesmod.terrain.field.Noise;
import io.github.menonng.biomesmod.terrain.field.Shaper;
import io.github.menonng.biomesmod.terrain.field.ShaperMode;
import net.minecraft.world.level.block.Blocks;

/**
 * {@code wetland}: land pulled down to a water table and broken into shallow open water, low hummocks (tree islands)
 * and a branching maze of channels (Everglades sawgrass "river of grass", Amazon várzea flooded forest).
 *
 * @param waterOffset  water table relative to sea level
 * @param dryCoverage  fraction of the ground that rises just above the water (hummocks, levees)
 * @param hummock      max hummock height above the water
 * @param shallow      depth of the open water between hummocks
 * @param channels     channel coverage (0 = none); channels are narrow, branching, slightly deeper
 */
public record WetlandMode(Common common, int waterOffset, double dryCoverage, double hummock, int shallow, double channels,
		double scale) implements ShaperMode {
	public static final MapCodec<WetlandMode> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Common.MAP_CODEC.forGetter(WetlandMode::common),
			Codec.intRange(-16, 32).optionalFieldOf("water_offset", 0).forGetter(WetlandMode::waterOffset),
			Codec.doubleRange(0, 1).optionalFieldOf("dry_coverage", 0.45).forGetter(WetlandMode::dryCoverage),
			Codec.doubleRange(0, 12).optionalFieldOf("hummock", 2.0).forGetter(WetlandMode::hummock),
			Codec.intRange(1, 6).optionalFieldOf("shallow", 1).forGetter(WetlandMode::shallow),
			Codec.doubleRange(0, 1).optionalFieldOf("channels", 0.25).forGetter(WetlandMode::channels),
			Codec.doubleRange(8, 1024).optionalFieldOf("scale", 70.0).forGetter(WetlandMode::scale)
	).apply(i, WetlandMode::new));

	@Override
	public Type type() {
		return Type.WETLAND;
	}

	@Override
	public Shaper createShaper(long seed) {
		return new S(seed);
	}

	private final class S extends Shaper {
		private final double dryT = Coverage.threshold(dryCoverage, 4);

		S(long seed) {
			super(seed);
		}

		@Override
		public FieldPalette defaultPalette() {
			return P.palette(P.layers(Blocks.GRASS_BLOCK, 1, Blocks.DIRT, 2), P.layers(Blocks.MUD, 2, Blocks.CLAY, 1),
					P.strata(Blocks.DIRT, 3, Blocks.CLAY, 1), 1024);
		}

		@Override
		public void column(ChunkFrame frame, int x, int z, int i, Column out) {
			int w = frame.seaLevel + waterOffset;   // water fills Y < w
			double n = noise.fbm(x, z, scale, 4);
			double g;
			if (n > dryT) {
				g = w + Math.min(hummock, (n - dryT) * hummock * 2.2);  // rises gently out of the water
			} else {
				g = w - 1 - shallow * Noise.smoothstep(dryT, dryT - 0.35, n);
			}
			if (channels > 0) {
				double ridge = noise.ridged(x + 811, z - 377, scale * 1.8, 3);
				double t = 1 - channels * 0.08;
				if (ridge > t) {
					g = Math.min(g, w - 2 - shallow);
				}
			}
			out.ground = g;
			out.water = w;
		}
	}
}
