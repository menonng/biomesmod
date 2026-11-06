package io.github.menonng.biomesmod.terrain.util;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Shared block palette for engine features: a surface cap plus horizontal strata repeating by world Y.
 * Used for layered sandstone (Monument Valley, Grand Canyon), travertine, basalt, ice, etc.
 *
 * <pre>
 * "palette": {
 *   "top": {"Name": "minecraft:grass_block"},            // optional, exposed upward-facing surface
 *   "top_depth": 1,                                         // optional, default 1
 *   "strata": [                                             // repeats every sum(thickness) blocks of Y
 *     {"block": {"Name": "minecraft:red_terracotta"}, "thickness": 4},
 *     {"block": {"Name": "minecraft:orange_terracotta"}, "thickness": 2}
 *   ],
 *   "strata_offset": 0                                      // optional Y shift of the pattern
 * }
 * </pre>
 *
 * Owner: coordinator. Engine agents use it as-is; propose changes instead of editing.
 */
public record StrataPalette(Optional<BlockState> top, int topDepth, List<Layer> strata, int strataOffset) {
	public record Layer(BlockState block, int thickness) {
		public static final Codec<Layer> CODEC = RecordCodecBuilder.create(i -> i.group(
				BlockState.CODEC.fieldOf("block").forGetter(Layer::block),
				Codec.intRange(1, 256).optionalFieldOf("thickness", 1).forGetter(Layer::thickness)
		).apply(i, Layer::new));
	}

	public static final Codec<StrataPalette> CODEC = RecordCodecBuilder.create(i -> i.group(
			BlockState.CODEC.optionalFieldOf("top").forGetter(StrataPalette::top),
			Codec.intRange(0, 16).optionalFieldOf("top_depth", 1).forGetter(StrataPalette::topDepth),
			Layer.CODEC.listOf().fieldOf("strata").forGetter(StrataPalette::strata),
			Codec.INT.optionalFieldOf("strata_offset", 0).forGetter(StrataPalette::strataOffset)
	).apply(i, StrataPalette::new));

	/** Strata block for world height y (ignores the top cap). */
	public BlockState strataAt(int y) {
		int period = 0;
		for (Layer layer : strata) {
			period += layer.thickness();
		}
		int k = Math.floorMod(y + strataOffset, period);
		for (Layer layer : strata) {
			if (k < layer.thickness()) {
				return layer.block();
			}
			k -= layer.thickness();
		}
		return strata.getFirst().block();
	}

	/**
	 * Block for a solid position at height y that lies {@code depthBelowSurface} blocks under the
	 * exposed surface of its column (0 = the surface block itself).
	 */
	public BlockState blockAt(int y, int depthBelowSurface) {
		if (top.isPresent() && depthBelowSurface < topDepth) {
			return top.get();
		}
		return strataAt(y);
	}
}
