package io.github.menonng.biomesmod.terrain.landform

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.AmethystClusterBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration

/**
 * `biomesmod:fallen_log`: an old trunk lying on the forest floor, following the ground, a stump at its root end, moss
 * along its back and shelf fungi on its flanks. Never overwrites terrain.
 */
class FallenLogFeature : Feature<FallenLogFeature.Config>(Config.CODEC) {
	data class Config(val log: BlockState, val carpet: BlockState, val bracket: BlockState, val length: Span) : FeatureConfiguration {
		companion object {
			@JvmField
			val CODEC: Codec<Config> = RecordCodecBuilder.create { i ->
				i.group(
					BlockState.CODEC.fieldOf("log").forGetter(Config::log),
					BlockState.CODEC.fieldOf("carpet").forGetter(Config::carpet),
					BlockState.CODEC.fieldOf("bracket").forGetter(Config::bracket),
					Span.CODEC.optionalFieldOf("length", Span.of(7.0, 14.0)).forGetter(Config::length),
				).apply(i) { log, carpet, bracket, length -> Config(log, carpet, bracket, length) }
			}
		}
	}

	override fun place(context: FeaturePlaceContext<Config>): Boolean {
		val level = context.level()
		val r = context.random()
		val cfg = context.config()
		val o = context.origin()
		if (!ChunkWriter.isTerrain(level.getBlockState(o.below()))) {
			return false
		}
		val dir = Direction.Plane.HORIZONTAL.getRandomDirection(r)
		val len = (cfg.length.min + r.nextFloat() * (cfg.length.max - cfg.length.min)).toInt()
		val p = BlockPos.MutableBlockPos()
		val side = dir.clockWise
		val log = if (cfg.log.hasProperty(RotatedPillarBlock.AXIS)) cfg.log.setValue(RotatedPillarBlock.AXIS, dir.axis) else cfg.log
		val upright = if (cfg.log.hasProperty(RotatedPillarBlock.AXIS)) cfg.log.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y) else cfg.log
		// Stump.
		for (y in 0 until 1 + r.nextInt(2)) {
			if (level.getBlockState(p.set(o.x, o.y + y, o.z)).isAir) level.setBlock(p, upright, 2)
		}
		var y = o.y
		var placed = 0
		for (s in 2 until len + 2) {
			val x = o.x + dir.stepX * s
			val z = o.z + dir.stepZ * s
			// Follow the ground one block at a time; stop at a drop or a wall.
			if (level.getBlockState(p.set(x, y - 1, z)).isAir) {
				if (ChunkWriter.isTerrain(level.getBlockState(p.set(x, y - 2, z)))) y-- else break
			}
			if (!level.getBlockState(p.set(x, y, z)).isAir && !level.getBlockState(p).canBeReplaced()) {
				if (level.getBlockState(p.set(x, y + 1, z)).isAir) y++ else break
			}
			level.setBlock(p.set(x, y, z), log, 2)
			placed++
			if (r.nextFloat() < 0.6 && level.getBlockState(p.set(x, y + 1, z)).isAir) level.setBlock(p, cfg.carpet, 2)
			for (sd in arrayOf(side, side.opposite)) {
				if (r.nextFloat() < 0.12 && level.getBlockState(p.set(x + sd.stepX, y, z + sd.stepZ)).isAir) {
					val b = if (cfg.bracket.hasProperty(AmethystClusterBlock.FACING)) cfg.bracket.setValue(AmethystClusterBlock.FACING, sd) else cfg.bracket
					level.setBlock(p, b, 2)
				}
			}
		}
		return placed > 0
	}
}
