package io.github.menonng.biomesmod.compat

import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState

/** Version shim: block and block item registration (1.21.1: plain properties). */
object BlockCompat {
	fun <T : Block> register(name: String, properties: BlockBehaviour.Properties, factory: (BlockBehaviour.Properties) -> T): T =
		Registry.register(BuiltInRegistries.BLOCK, Ids.id(name), factory(properties))

	fun block(name: String, properties: BlockBehaviour.Properties): Block = register(name, properties, ::Block)

	/** Leaves coloured by their texture (no biome tint); 1.21.1 leaves have no particle colour. */
	fun leaves(name: String, properties: BlockBehaviour.Properties, particleArgb: Int): Block = register(name, properties, ::LeavesBlock)

	/** Walk-through plant properties (the method name differs between versions). */
	fun plantProperties(): BlockBehaviour.Properties = BlockBehaviour.Properties.of().noCollission().instabreak()

	fun noCollision(properties: BlockBehaviour.Properties): BlockBehaviour.Properties = properties.noCollission()

	fun <T : BlockEntity> blockEntity(name: String, factory: (BlockPos, BlockState) -> T, vararg blocks: Block): BlockEntityType<T> =
		Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Ids.id(name),
			BlockEntityType.Builder.of({ p, s -> factory(p, s) }, *blocks).build(null))

	/** [tall]: a two-block item such as a door. */
	fun item(name: String, block: Block, tall: Boolean = false): Item =
		Registry.register(BuiltInRegistries.ITEM, Ids.id(name),
			if (tall) net.minecraft.world.item.DoubleHighBlockItem(block, Item.Properties()) else BlockItem(block, Item.Properties()))
}
