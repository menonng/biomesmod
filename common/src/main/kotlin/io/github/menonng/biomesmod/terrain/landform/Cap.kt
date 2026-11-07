package io.github.menonng.biomesmod.terrain.landform

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.Holder
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.placement.PlacedFeature
import java.util.Optional

/**
 * Vegetated cap on flat landform tops (pillar tops, butte tops, stack tops, islands).
 *
 * ```
 * "cap": {
 *   "block": {"Name": "minecraft:grass_block"},   // surface block (default grass_block)
 *   "under": {"Name": "minecraft:dirt"},           // below the surface (default dirt)
 *   "depth": 2,                                     // block + under thickness (default 2)
 *   "feature": "minecraft:pine_checked",            // optional placed feature, e.g. a "*_checked" tree
 *   "chance": 0.03                                  // chance per cap column to place the feature
 * }
 * ```
 */
data class Cap(val block: BlockState, val under: BlockState, val depth: Int, val feature: Optional<Holder<PlacedFeature>>, val chance: Float) {
	companion object {
		@JvmField
		val CODEC: Codec<Cap> = RecordCodecBuilder.create { i ->
			i.group(
				BlockState.CODEC.optionalFieldOf("block", B.of("grass_block")).forGetter(Cap::block),
				BlockState.CODEC.optionalFieldOf("under", B.of("dirt")).forGetter(Cap::under),
				Codec.intRange(0, 8).optionalFieldOf("depth", 2).forGetter(Cap::depth),
				PlacedFeature.CODEC.optionalFieldOf("feature").forGetter(Cap::feature),
				Codec.floatRange(0f, 1f).optionalFieldOf("chance", 0.03f).forGetter(Cap::chance),
			).apply(i) { block, under, depth, feature, chance -> Cap(block, under, depth, feature, chance) }
		}

		internal fun grass(): Cap = Cap(B.of("grass_block"), B.of("dirt"), 2, Optional.empty(), 0f)
	}
}
