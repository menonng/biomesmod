package io.github.menonng.biomesmod.block

import io.github.menonng.biomesmod.ModBlocks
import io.github.menonng.biomesmod.compat.LandformCompat
import io.github.menonng.biomesmod.terrain.landform.GiantTreeFeature
import io.github.menonng.biomesmod.terrain.landform.LandformFeatures
import io.github.menonng.biomesmod.terrain.landform.Span
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.BonemealableBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.VoxelShape
import java.util.Optional

/**
 * Ghostwood sapling. Alone it grows into a young white tree; nine planted in a 3x3 square grow together into an
 * ancient ghostwood, the giant of the White Old Growth (the same tree that fills the heart of that biome).
 */
class GhostwoodSaplingBlock(properties: BlockBehaviour.Properties) : Block(properties), BonemealableBlock {
	override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

	override fun canSurvive(state: BlockState, level: LevelReader, pos: BlockPos): Boolean = LandformCompat.isSoil(level.getBlockState(pos.below()))

	override fun randomTick(state: BlockState, level: ServerLevel, pos: BlockPos, random: RandomSource) {
		if (random.nextInt(8) == 0) {
			grow(level, pos, random)
		}
	}

	override fun isValidBonemealTarget(level: LevelReader, pos: BlockPos, state: BlockState): Boolean = true

	override fun isBonemealSuccess(level: Level, random: RandomSource, pos: BlockPos, state: BlockState): Boolean = random.nextFloat() < 0.45f

	override fun performBonemeal(level: ServerLevel, random: RandomSource, pos: BlockPos, state: BlockState) {
		grow(level, pos, random)
	}

	private fun grow(level: ServerLevel, pos: BlockPos, random: RandomSource) {
		val generator = level.chunkSource.generator
		// Nine saplings in a square: find the square this one belongs to.
		for (ox in -2..0) {
			for (oz in -2..0) {
				val all = (0..2).all { i -> (0..2).all { j -> level.getBlockState(pos.offset(ox + i, 0, oz + j)).block === this } }
				if (!all) continue
				val centre = pos.offset(ox + 1, 0, oz + 1)
				clear(level, centre, Blocks.AIR.defaultBlockState())
				if (!LandformFeatures.GIANT_TREE.place(ancient(), level, generator, random, centre)) {
					clear(level, centre, defaultBlockState())
				}
				return
			}
		}
		level.setBlock(pos, Blocks.AIR.defaultBlockState(), 4)
		if (!LandformFeatures.GIANT_TREE.place(young(), level, generator, random, pos)) {
			level.setBlock(pos, defaultBlockState(), 4)
		}
	}

	private fun clear(level: ServerLevel, centre: BlockPos, state: BlockState) {
		for (i in -1..1) for (j in -1..1) level.setBlock(centre.offset(i, 0, j), state, 4)
	}

	companion object {
		private val SHAPE: VoxelShape = box(2.0, 0.0, 2.0, 14.0, 12.0, 14.0)

		private fun dress(big: Boolean) = GiantTreeFeature.Dress(
			Optional.of(ModBlocks.SILVER_BEARD.defaultBlockState()), if (big) 0.12f else 0.07f,
			Optional.of(ModBlocks.MOON_BRACKET.defaultBlockState()), if (big) 0.08f else 0.03f,
			if (big) Optional.of(ModBlocks.GHOSTWOOD_HOLLOW.defaultBlockState()) else Optional.empty(), 0.5f,
			if (big) Optional.of(ModBlocks.PALE_MOSS_CARPET.defaultBlockState()) else Optional.empty(), 0.5f,
		)

		/** The ancient ghostwood (also used by the biome's worldgen: keep in sync with white_old_growth_giants). */
		fun ancient(): GiantTreeFeature.Config = GiantTreeFeature.Config("broad", ModBlocks.GHOSTWOOD_LOG.defaultBlockState(),
			ModBlocks.GHOSTWOOD_WOOD.defaultBlockState(), ModBlocks.GHOSTWOOD_LEAVES.defaultBlockState(), Span.of(38.0, 52.0), Span.of(2.6, 3.4),
			Span.of(12.0, 15.0), 0.15f, dress(true))

		fun young(): GiantTreeFeature.Config = GiantTreeFeature.Config("broad", ModBlocks.GHOSTWOOD_LOG.defaultBlockState(),
			ModBlocks.GHOSTWOOD_WOOD.defaultBlockState(), ModBlocks.GHOSTWOOD_LEAVES.defaultBlockState(), Span.of(10.0, 15.0), Span.of(0.5, 0.8),
			Span.of(4.0, 6.0), 0.05f, dress(false))
	}
}
