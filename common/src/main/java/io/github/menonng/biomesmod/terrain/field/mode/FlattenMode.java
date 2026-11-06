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
import java.util.Optional;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code flatten}: a dead-flat pan (Salar de Uyuni, Lake Gairdner). Optional 1-block water film in noise patches
 * (the Uyuni mirror) and a polygonal crust pattern of raised salt ridges drawn with a second surface block.
 *
 * @param level         absolute pan Y; default sea level + {@code offset}
 * @param offset        pan height above sea level when {@code level} is absent
 * @param relief        amplitude (blocks) of a very gentle undulation; keep under 1 for a true pan
 * @param waterCoverage fraction of the pan covered by a 1-block water film (0 = dry)
 * @param waterScale    size of the film patches
 * @param crust         fraction of the pan showing polygon ridges (0 = none)
 * @param crustCell     polygon size in blocks
 * @param crustBlock    block of the polygon ridges
 */
public record FlattenMode(Common common, Optional<Integer> level, int offset, double relief, double waterCoverage, double waterScale,
		double crust, double crustCell, Optional<BlockState> crustBlock) implements ShaperMode {
	public static final MapCodec<FlattenMode> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Common.MAP_CODEC.forGetter(FlattenMode::common),
			Codec.INT.optionalFieldOf("level").forGetter(FlattenMode::level),
			Codec.intRange(-64, 128).optionalFieldOf("offset", 2).forGetter(FlattenMode::offset),
			Codec.doubleRange(0, 8).optionalFieldOf("relief", 0.0).forGetter(FlattenMode::relief),
			Codec.doubleRange(0, 1).optionalFieldOf("water_coverage", 0.0).forGetter(FlattenMode::waterCoverage),
			Codec.doubleRange(8, 4096).optionalFieldOf("water_scale", 160.0).forGetter(FlattenMode::waterScale),
			Codec.doubleRange(0, 1).optionalFieldOf("crust", 0.0).forGetter(FlattenMode::crust),
			Codec.doubleRange(3, 64).optionalFieldOf("crust_cell", 7.0).forGetter(FlattenMode::crustCell),
			BlockState.CODEC.optionalFieldOf("crust_block").forGetter(FlattenMode::crustBlock)
	).apply(i, FlattenMode::new));

	@Override
	public Type type() {
		return Type.FLATTEN;
	}

	@Override
	public Shaper createShaper(long seed) {
		return new S(seed);
	}

	private final class S extends Shaper {
		private final double waterT = Coverage.threshold(waterCoverage, 3);
		private final double crustT = Coverage.threshold(crust, 2);
		private final BlockState ridge = crustBlock.orElse(P.id("white_concrete_powder"));

		S(long seed) {
			super(seed);
		}

		@Override
		public FieldPalette defaultPalette() {
			return P.palette(P.layers(Blocks.CALCITE, 2, "white_terracotta", 2), P.layers(Blocks.CALCITE, 2),
					P.strata(Blocks.SMOOTH_SANDSTONE, 3, "white_terracotta", 1), 6);
		}

		@Override
		public void column(ChunkFrame frame, int x, int z, int i, Column out) {
			int pan = level.orElse(frame.seaLevel + offset);
			out.ground = pan + (relief > 0 ? relief * noise.fbm(x, z, 220, 2) : 0);
			if (waterCoverage > 0 && noise.fbm(x + 4111, z - 977, waterScale, 3) > waterT) {
				out.water = pan + 2; // one block of water over the pan
			}
			if (crust > 0 && noise.fbm(x - 3301, z + 1907, 90, 2) > crustT) {
				// Polygon ridges: Voronoi cell borders, slightly wobbly so they never look ruled.
				double wx = x + 1.6 * noise.simplex(x / 11.0, z / 11.0);
				double wz = z + 1.6 * noise.simplex(x / 11.0 + 31.7, z / 11.0);
				if (noise.voronoiEdge(wx / crustCell, wz / crustCell, 0.85) < 0.09) {
					out.surface = ridge;
					out.surfaceDepth = 1;
				}
			}
		}
	}
}
