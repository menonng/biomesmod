package io.github.menonng.biomesmod.platform

import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import java.nio.file.Path

/**
 * What the shared code needs from the mod loader (Fabric, Forge or NeoForge). Each loader's entry point sets
 * [Loader.hooks] before anything else runs.
 */
interface LoaderHooks {
	/** The game's config folder. */
	val configDir: Path

	/** Adds items to the Natural Blocks creative tab. */
	fun naturalTab(items: List<Item>)

	/** Adds items to the Building Blocks creative tab. */
	fun buildingTab(items: List<Item>)

	/** Lets fire catch and spread on a block, like the vanilla blocks of the same kind. */
	fun flammable(block: Block, burn: Int, spread: Int)

	/** Lets a composter take the block's item. */
	fun compostable(block: Block, chance: Float)
}

object Loader {
	lateinit var hooks: LoaderHooks
}
