package io.github.menonng.biomesmod.terrain.util

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.level.block.state.BlockState
import java.util.Optional

/**
 * Shared block palette for engine features: a surface cap plus horizontal strata repeating by world Y. Used for
 * layered sandstone (Monument Valley, Grand Canyon), travertine, basalt, ice, etc.
 *
 * ```
 * "palette": {
 *   "top": {"Name": "minecraft:grass_block"},            // optional, exposed upward-facing surface
 *   "top_depth": 1,                                         // optional, default 1
 *   "strata": [                                             // repeats every sum(thickness) blocks of Y
 *     {"block": {"Name": "minecraft:red_terracotta"}, "thickness": 4},
 *     {"block": {"Name": "minecraft:orange_terracotta"}, "thickness": 2}
 *   ],
 *   "strata_offset": 0                                      // optional Y shift of the pattern
 * }
 * ```
 */
data class StrataPalette(val top: Optional<BlockState>, val topDepth: Int, val strata: List<Layer>, val strataOffset: Int) {
	data class Layer(val block: BlockState, val thickness: Int) {
		companion object {
			@JvmField
			val CODEC: Codec<Layer> = RecordCodecBuilder.create { i ->
				i.group(
					BlockState.CODEC.fieldOf("block").forGetter(Layer::block),
					Codec.intRange(1, 256).optionalFieldOf("thickness", 1).forGetter(Layer::thickness),
				).apply(i) { block, thickness -> Layer(block, thickness) }
			}
		}
	}

	private val period: Int = strata.sumOf { it.thickness }

	/** Strata block for world height y (ignores the top cap). */
	fun strataAt(y: Int): BlockState {
		var k = Math.floorMod(y + strataOffset, period)
		for (layer in strata) {
			if (k < layer.thickness) {
				return layer.block
			}
			k -= layer.thickness
		}
		return strata.first().block
	}

	/**
	 * Block for a solid position at height y that lies [depthBelowSurface] blocks under the exposed surface of its
	 * column (0 = the surface block itself).
	 */
	fun blockAt(y: Int, depthBelowSurface: Int): BlockState =
		if (top.isPresent && depthBelowSurface < topDepth) top.get() else strataAt(y)

	companion object {
		@JvmField
		val CODEC: Codec<StrataPalette> = RecordCodecBuilder.create { i ->
			i.group(
				BlockState.CODEC.optionalFieldOf("top").forGetter(StrataPalette::top),
				Codec.intRange(0, 16).optionalFieldOf("top_depth", 1).forGetter(StrataPalette::topDepth),
				Layer.CODEC.listOf().fieldOf("strata").forGetter(StrataPalette::strata),
				Codec.INT.optionalFieldOf("strata_offset", 0).forGetter(StrataPalette::strataOffset),
			).apply(i) { top, topDepth, strata, strataOffset -> StrataPalette(top, topDepth, strata, strataOffset) }
		}
	}
}
