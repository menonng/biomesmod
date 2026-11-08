package io.github.menonng.biomesmod.compat

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.PotentSulfurBlock
import net.minecraft.world.level.block.state.properties.PotentSulfurState
import net.minecraft.world.level.block.state.BlockState

/** Version shim for the landform engine (26.2). Same signatures as the 1.21.1 copy. */
object LandformCompat {
	/** One above the highest buildable Y (getMaxY() is inclusive in 26.2). */
	fun maxY(level: LevelHeightAccessor): Int = level.maxY + 1

	/**
	 * Default state of a block by id ("minecraft:red_terracotta"). Dyed blocks have no stable field across
	 * versions (26.2 groups them in colour collections), so landform defaults go through here.
	 */
	fun block(id: String): BlockState {
		val block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id))
		require(block !== Blocks.AIR || id.endsWith(":air")) { "Unknown block $id" }
		return block.defaultBlockState()
	}

	/**
	 * Vanilla geyser (26.2): {block under the vent, the vent}. Magma under potent sulfur under up to four water source
	 * blocks erupts periodically (higher with more water). Null where vanilla has no geysers.
	 */
	fun geyserVent(): Array<BlockState>? = arrayOf(
		Blocks.MAGMA_BLOCK.defaultBlockState(),
		Blocks.POTENT_SULFUR.defaultBlockState().setValue(PotentSulfurBlock.STATE, PotentSulfurState.DORMANT),
	)

	/** Ground trees and plants grow on (26.2: dirt, grass blocks, moss and mud are "substrate_overworld"). */
	fun isSoil(state: BlockState): Boolean = state.`is`(net.minecraft.tags.BlockTags.SUBSTRATE_OVERWORLD) || state.`is`(net.minecraft.tags.BlockTags.DIRT)
}
