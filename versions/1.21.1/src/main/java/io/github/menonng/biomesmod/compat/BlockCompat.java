package io.github.menonng.biomesmod.compat;

import java.util.List;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** Version shim: block and block item registration (1.21.1: plain properties). */
public final class BlockCompat {
	private BlockCompat() {
	}

	public static Block block(String name, BlockBehaviour.Properties properties) {
		return Registry.register(BuiltInRegistries.BLOCK, Ids.id(name), new Block(properties));
	}

	/** Adds the items to the Natural Blocks creative tab. */
	public static void naturalTab(List<Item> items) {
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.NATURAL_BLOCKS).register(entries -> items.forEach(entries::accept));
	}

	public static Item item(String name, Block block) {
		return Registry.register(BuiltInRegistries.ITEM, Ids.id(name), new BlockItem(block, new Item.Properties()));
	}
}
