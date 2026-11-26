package io.github.menonng.biomesmod.fabric

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block

/** The Fabric API calls that differ between Minecraft versions (26.2). */
object FabricVersion {
	fun naturalTab(items: List<Item>) {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.NATURAL_BLOCKS).register { output -> items.forEach { output.accept(it) } }
	}

	fun buildingTab(items: List<Item>) {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.BUILDING_BLOCKS).register { output -> items.forEach { output.accept(it) } }
	}

	/** 26.2 picks each quad's render layer from its texture's alpha, so cut-out textures need no registration. */
	fun cutout(blocks: List<Block>, mipped: List<Block>) {}
}
