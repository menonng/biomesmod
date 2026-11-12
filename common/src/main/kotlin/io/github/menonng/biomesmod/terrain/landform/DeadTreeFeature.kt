package io.github.menonng.biomesmod.terrain.landform

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration

/**
 * `biomesmod:dead_tree`: the bare skeleton of a tree that died standing (the camel thorns of Deadvlei): a short trunk
 * that leans a little and forks into a few leafless limbs. Grows only on the blocks listed in `ground`.
 */
class DeadTreeFeature : Feature<DeadTreeFeature.Config>(Config.CODEC) {
	data class Config(val log: BlockState, val ground: List<BlockState>, val height: Span) : FeatureConfiguration {
		companion object {
			@JvmField
			val CODEC: Codec<Config> = RecordCodecBuilder.create { i ->
				i.group(
					BlockState.CODEC.fieldOf("log").forGetter(Config::log),
					BlockState.CODEC.listOf().fieldOf("ground").forGetter(Config::ground),
					Span.CODEC.optionalFieldOf("height", Span.of(3.0, 5.0)).forGetter(Config::height),
				).apply(i) { log, ground, height -> Config(log, ground, height) }
			}
		}
	}

	override fun place(context: FeaturePlaceContext<Config>): Boolean {
		val level = context.level()
		val r = context.random()
		val cfg = context.config()
		val o = context.origin()
		val below = level.getBlockState(o.below())
		if (cfg.ground.none { it.block === below.block } || !level.getBlockState(o).isAir) {
			return false
		}
		fun log(axis: Direction.Axis): BlockState = if (cfg.log.hasProperty(RotatedPillarBlock.AXIS)) cfg.log.setValue(RotatedPillarBlock.AXIS, axis) else cfg.log
		val p = BlockPos.MutableBlockPos()
		fun put(x: Int, y: Int, z: Int, axis: Direction.Axis): Boolean {
			if (!level.getBlockState(p.set(x, y, z)).isAir) return false
			level.setBlock(p, log(axis), 2)
			return true
		}
		val h = (cfg.height.min + r.nextFloat() * (cfg.height.max - cfg.height.min + 1)).toInt()
		var x = o.x
		var z = o.z
		val lean = Direction.Plane.HORIZONTAL.getRandomDirection(r)
		for (y in 0 until h) {
			// One sideways step somewhere up the trunk: old trunks never stand plumb.
			if (y == h / 2 && r.nextBoolean()) {
				x += lean.stepX
				z += lean.stepZ
			}
			if (!put(x, o.y + y, z, Direction.Axis.Y)) return y > 0
		}
		// Limbs: two to four, each running out and up from the upper trunk.
		val dirs = Direction.Plane.HORIZONTAL.shuffledCopy(r)
		val limbs = 2 + r.nextInt(3)
		for (k in 0 until limbs) {
			val d = dirs[k % dirs.size]
			var bx = x
			var bz = z
			var by = o.y + h - 1 - r.nextInt(2)
			val len = 1 + r.nextInt(3)
			for (s in 0 until len) {
				bx += d.stepX
				bz += d.stepZ
				if (s > 0 && r.nextFloat() < 0.6) by++
				if (!put(bx, by, bz, d.axis)) break
			}
			if (r.nextBoolean()) put(bx, by + 1, bz, Direction.Axis.Y)
		}
		return true
	}
}
