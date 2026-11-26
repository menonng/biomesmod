package io.github.menonng.biomesmod.compat

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState

/** Version shim for the landform engine (1.20.1). Same signatures as the 26.2 copy. */
object LandformCompat {
	/** One above the highest buildable Y (getMaxBuildHeight() in 1.20.1). */
	fun maxY(level: LevelHeightAccessor): Int = level.maxBuildHeight

	/**
	 * Default state of a block by id ("minecraft:red_terracotta"). Dyed blocks have no stable field across
	 * versions (26.2 groups them in colour collections), so landform defaults go through here.
	 */
	fun block(id: String): BlockState {
		val block = BuiltInRegistries.BLOCK.get(ResourceLocation(id))
		require(block !== Blocks.AIR || id.endsWith(":air")) { "Unknown block $id" }
		return block.defaultBlockState()
	}

	/** Vanilla geyser blocks {under the vent, the vent}: 1.20.1 has none (the mod's geyser vent erupts instead). */
	fun geyserVent(): Array<BlockState>? = null

	/** Ground trees and plants grow on (1.20.1: the dirt tag holds grass, podzol, moss and mud too). */
	fun isSoil(state: BlockState): Boolean = state.`is`(net.minecraft.tags.BlockTags.DIRT)
}
