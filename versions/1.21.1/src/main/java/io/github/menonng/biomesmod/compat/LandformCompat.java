package io.github.menonng.biomesmod.compat;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Version shim for the landform engine (1.21.1). Same signatures as the 26.2 copy.
 * Owner: landform-engineer.
 */
public final class LandformCompat {
	private LandformCompat() {
	}

	/** One above the highest buildable Y (getMaxBuildHeight() in 1.21.1). */
	public static int maxY(LevelHeightAccessor level) {
		return level.getMaxBuildHeight();
	}

	/**
	 * Default state of a block by id ("minecraft:red_terracotta"). Dyed blocks have no stable Java field
	 * across versions (26.2 groups them in colour collections), so landform defaults go through here.
	 */
	public static BlockState block(String id) {
		Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
		if (block == Blocks.AIR && !id.endsWith(":air")) {
			throw new IllegalArgumentException("Unknown block " + id);
		}
		return block.defaultBlockState();
	}
}
