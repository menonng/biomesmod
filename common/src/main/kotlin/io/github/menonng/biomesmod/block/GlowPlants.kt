package io.github.menonng.biomesmod.block

import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.AmethystClusterBlock
import net.minecraft.world.level.block.HangingRootsBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState

/**
 * A plant or fungus that grows on any face (floor, wall or ceiling, like an amethyst cluster) and now and then lets a
 * particle drift off: the star bloom of the Star Abyss, the moon bracket fungus on white old-growth trunks.
 */
class GlowClusterBlock(height: Float, width: Float, properties: BlockBehaviour.Properties, private val particle: () -> ParticleOptions,
	private val chance: Int) : AmethystClusterBlock(height, width, properties) {
	override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
		if (random.nextInt(chance) == 0) {
			level.addParticle(particle(), pos.x + 0.2 + random.nextDouble() * 0.6, pos.y + 0.3 + random.nextDouble() * 0.5,
				pos.z + 0.2 + random.nextDouble() * 0.6, (random.nextDouble() - 0.5) * 0.01, 0.01 + random.nextDouble() * 0.02,
				(random.nextDouble() - 0.5) * 0.01)
		}
	}
}

/** A hanging plant (survives under any sturdy block, leaves included): silver beard lichen, glowing astral roots. */
class HangingPlantBlock(properties: BlockBehaviour.Properties, private val particle: (() -> ParticleOptions)?) : HangingRootsBlock(properties) {
	override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
		val p = particle ?: return
		if (random.nextInt(10) == 0) {
			level.addParticle(p(), pos.x + random.nextDouble(), pos.y + random.nextDouble() * 0.4, pos.z + random.nextDouble(), 0.0, -0.01, 0.0)
		}
	}
}

/** A hollow in an old trunk from which pale wisps drift out (white old growth). */
class WispLogBlock(properties: BlockBehaviour.Properties, private val particle: () -> ParticleOptions) : RotatedPillarBlock(properties) {
	override fun animateTick(state: BlockState, level: Level, pos: BlockPos, random: RandomSource) {
		if (random.nextInt(3) == 0) {
			level.addParticle(particle(), pos.x + random.nextDouble(), pos.y + random.nextDouble(), pos.z + random.nextDouble(),
				(random.nextDouble() - 0.5) * 0.02, 0.008 + random.nextDouble() * 0.01, (random.nextDouble() - 0.5) * 0.02)
		}
	}
}
