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
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code islands}: a shallow sea scattered with islands of every size: an archipelago (Exuma Cays), or, with low
 * {@code height} and small {@code scale}, an eroded tidal coast of sandbars, islets and tide pools. Island outlines
 * come from domain-warped multi-octave noise, so they are lobed and varied, with sand rims and shoals around them;
 * {@code rocky} turns some islands into steep rock outcrops instead.
 *
 * @param coverage    fraction of the area above water
 * @param scale       typical island size (noise scale, blocks)
 * @param height      highest island top above sea level
 * @param beach       height band above the sea that is sand
 * @param depth       sea depth far from any island (the bank floor)
 * @param rocky       fraction of islands that are rocky
 */
public record IslandsMode(Common common, double coverage, double scale, double height, int beach, int depth, double rocky)
		implements ShaperMode {
	public static final MapCodec<IslandsMode> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Common.MAP_CODEC.forGetter(IslandsMode::common),
			Codec.doubleRange(0, 1).optionalFieldOf("coverage", 0.22).forGetter(IslandsMode::coverage),
			Codec.doubleRange(8, 2048).optionalFieldOf("scale", 70.0).forGetter(IslandsMode::scale),
			Codec.doubleRange(1, 80).optionalFieldOf("height", 9.0).forGetter(IslandsMode::height),
			Codec.intRange(0, 12).optionalFieldOf("beach", 2).forGetter(IslandsMode::beach),
			Codec.intRange(1, 40).optionalFieldOf("depth", 6).forGetter(IslandsMode::depth),
			Codec.doubleRange(0, 1).optionalFieldOf("rocky", 0.15).forGetter(IslandsMode::rocky)
	).apply(i, IslandsMode::new));

	private static final BlockState SAND = Blocks.SAND.defaultBlockState();
	private static final BlockState STONE = Blocks.STONE.defaultBlockState();

	@Override
	public Type type() {
		return Type.ISLANDS;
	}

	@Override
	public Shaper createShaper(long seed) {
		return new S(seed);
	}

	private final class S extends Shaper {
		private final double t = Coverage.threshold(coverage, 4);

		S(long seed) {
			super(seed);
		}

		@Override
		public FieldPalette defaultPalette() {
			return P.palette(P.layers(Blocks.GRASS_BLOCK, 1, Blocks.DIRT, 3), P.layers(Blocks.SAND, 3, Blocks.SANDSTONE, 2),
					P.strata(Blocks.STONE, 4, Blocks.ANDESITE, 1), 3);
		}

		@Override
		public void column(ChunkFrame frame, int x, int z, int i, Column out) {
			int sea = frame.seaLevel;
			double wx = x + scale * 0.35 * noise.fbm(x, z, scale * 1.3, 2);
			double wz = z + scale * 0.35 * noise.fbm(x + 913, z - 377, scale * 1.3, 2);
			double n = noise.fbm(wx, wz, scale, 4);
			out.water = sea;
			if (n > t) {
				double e = Math.min(1, (n - t) / (Coverage.std(4) * 1.6));
				boolean rock = noise.fbm(wx + 4091, wz - 2203, scale * 1.4, 1) > Coverage.threshold(rocky, 1);
				double h = rock ? height * 1.6 * Math.pow(e, 0.45) : height * Math.pow(e, 1.2);
				out.ground = sea + h;
				if (rock) {
					out.surface = STONE;
					out.surfaceDepth = 2;
				} else if (h < beach + 0.5) {
					out.surface = SAND;
					out.surfaceDepth = 3;
				}
			} else {
				// Shoals: shallow near islands, the bank floor further out, with sandy ripples.
				double s = Noise.smoothstep(t, t - 0.45, n);
				out.ground = sea - 1 - depth * s + 0.6 * noise.fbm(x, z, 14, 2) * s;
			}
		}
	}
}
