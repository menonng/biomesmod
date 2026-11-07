package io.github.menonng.biomesmod.compat

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour

/** Version shim: block and block item registration (1.21.1: plain properties). */
object BlockCompat {
	fun block(name: String, properties: BlockBehaviour.Properties): Block =
		Registry.register(BuiltInRegistries.BLOCK, Ids.id(name), Block(properties))

	/** Adds the items to the Natural Blocks creative tab. */
	fun naturalTab(items: List<Item>) {
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.NATURAL_BLOCKS).register { entries -> items.forEach { entries.accept(it) } }
	}

	fun item(name: String, block: Block): Item =
		Registry.register(BuiltInRegistries.ITEM, Ids.id(name), BlockItem(block, Item.Properties()))
}
