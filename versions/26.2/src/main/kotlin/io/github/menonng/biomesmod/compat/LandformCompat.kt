package io.github.menonng.biomesmod.compat

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.block.Blocks
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
}
