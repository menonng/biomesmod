package io.github.menonng.biomesmod.compat

import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour

/** Version shim: block and block item registration (26.2: properties carry their registry id). */
object BlockCompat {
	fun block(name: String, properties: BlockBehaviour.Properties): Block {
		val key = ResourceKey.create(Registries.BLOCK, Ids.id(name))
		return Registry.register(BuiltInRegistries.BLOCK, key, Block(properties.setId(key)))
	}

	/** Adds the items to the Natural Blocks creative tab. */
	fun naturalTab(items: List<Item>) {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.NATURAL_BLOCKS).register { output -> items.forEach { output.accept(it) } }
	}

	fun item(name: String, block: Block): Item {
		val key = ResourceKey.create(Registries.ITEM, Ids.id(name))
		return Registry.register(BuiltInRegistries.ITEM, key, BlockItem(block, Item.Properties().setId(key).useBlockDescriptionPrefix()))
	}
}
