package io.github.menonng.biomesmod.fabric

import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents
import net.minecraft.client.renderer.RenderType
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block

/** The Fabric API calls that differ between Minecraft versions (1.21.1). */
object FabricVersion {
	fun naturalTab(items: List<Item>) {
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.NATURAL_BLOCKS).register { entries -> items.forEach { entries.accept(it) } }
	}

	fun buildingTab(items: List<Item>) {
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.BUILDING_BLOCKS).register { entries -> items.forEach { entries.accept(it) } }
	}

	/** Client only: cut-out render layers (plants, crystals) and mipped cut-out (leaves). */
	fun cutout(blocks: List<Block>, mipped: List<Block>) {
		BlockRenderLayerMap.INSTANCE.putBlocks(RenderType.cutout(), *blocks.toTypedArray())
		BlockRenderLayerMap.INSTANCE.putBlocks(RenderType.cutoutMipped(), *mipped.toTypedArray())
	}
}
