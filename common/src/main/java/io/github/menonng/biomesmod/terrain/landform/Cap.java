package io.github.menonng.biomesmod.terrain.landform;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * Vegetated cap on flat landform tops (pillar tops, butte tops, stack tops, islands).
 *
 * <pre>
 * "cap": {
 *   "block": {"Name": "minecraft:grass_block"},   // surface block (default grass_block)
 *   "under": {"Name": "minecraft:dirt"},           // below the surface (default dirt)
 *   "depth": 2,                                     // block + under thickness (default 2)
 *   "feature": "minecraft:pine_checked",            // optional placed feature, e.g. a "*_checked" tree
 *   "chance": 0.03                                  // chance per cap column to place the feature
 * }
 * </pre>
 */
public record Cap(BlockState block, BlockState under, int depth, Optional<Holder<PlacedFeature>> feature, float chance) {
	public static final Codec<Cap> CODEC = RecordCodecBuilder.create(i -> i.group(
			BlockState.CODEC.optionalFieldOf("block", B.of("grass_block")).forGetter(Cap::block),
			BlockState.CODEC.optionalFieldOf("under", B.of("dirt")).forGetter(Cap::under),
			Codec.intRange(0, 8).optionalFieldOf("depth", 2).forGetter(Cap::depth),
			PlacedFeature.CODEC.optionalFieldOf("feature").forGetter(Cap::feature),
			Codec.floatRange(0, 1).optionalFieldOf("chance", 0.03f).forGetter(Cap::chance)
	).apply(i, Cap::new));

	static Cap grass() {
		return new Cap(B.of("grass_block"), B.of("dirt"), 2, Optional.empty(), 0);
	}
}
