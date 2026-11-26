package io.github.menonng.biomesmod.compat

import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.core.particles.ColorParticleOption
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.UntintedParticleLeavesBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState

/** Version shim: block and block item registration (26.2: properties carry their registry id). */
object BlockCompat {
	fun <T : Block> register(name: String, properties: BlockBehaviour.Properties, factory: (BlockBehaviour.Properties) -> T): T {
		val key = ResourceKey.create(Registries.BLOCK, Ids.id(name))
		return Registry.register(BuiltInRegistries.BLOCK, key, factory(properties.setId(key)))
	}

	fun block(name: String, properties: BlockBehaviour.Properties): Block = register(name, properties, ::Block)

	/** Leaves coloured by their texture (no biome tint); [particleArgb] tints the falling leaf particles. */
	fun leaves(name: String, properties: BlockBehaviour.Properties, particleArgb: Int): Block =
		register(name, properties) { UntintedParticleLeavesBlock(0.01f, ColorParticleOption.create(ParticleTypes.TINTED_LEAVES, particleArgb), it) }

	/** Walk-through plant properties (the method name differs between versions). */
	fun plantProperties(): BlockBehaviour.Properties = BlockBehaviour.Properties.of().noCollision().instabreak()

	fun noCollision(properties: BlockBehaviour.Properties): BlockBehaviour.Properties = properties.noCollision()

	fun <T : BlockEntity> blockEntity(name: String, factory: (BlockPos, BlockState) -> T, vararg blocks: Block): BlockEntityType<T> =
		Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, Ids.id(name), BlockEntityType({ p, s -> factory(p, s) }, setOf(*blocks)))

	/** [tall]: a two-block item such as a door. */
	fun item(name: String, block: Block, tall: Boolean = false): Item {
		val key = ResourceKey.create(Registries.ITEM, Ids.id(name))
		val properties = Item.Properties().setId(key).useBlockDescriptionPrefix()
		return Registry.register(BuiltInRegistries.ITEM, key,
			if (tall) net.minecraft.world.item.DoubleHighBlockItem(block, properties) else BlockItem(block, properties))
	}
}
