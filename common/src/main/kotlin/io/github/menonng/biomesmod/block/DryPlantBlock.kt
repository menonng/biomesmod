package io.github.menonng.biomesmod.block

import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.FlowerBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState

/** A plant of dry, stony ground: besides soil it roots in sand, gravel and terracotta (tussock grass of the puna and the steppe). */
class DryPlantBlock(properties: BlockBehaviour.Properties) : FlowerBlock(MobEffects.HUNGER, 4f, properties) {
	override fun mayPlaceOn(state: BlockState, level: BlockGetter, pos: BlockPos): Boolean =
		super.mayPlaceOn(state, level, pos) || state.`is`(BlockTags.SAND) || state.`is`(BlockTags.TERRACOTTA) || state.`is`(Blocks.GRAVEL)
}
