package io.github.menonng.biomesmod.terrain.landform

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * `biomesmod:bottle_tree`: a succulent bottle tree (Socotra's Adenium, the desert rose): a fat swollen grey base that
 * narrows into a few short, thick, upturned branches, each tipped with a tuft of leaves and flowers. Grows on dirt,
 * sand or stone; never overwrites solid blocks.
 */
class BottleTreeFeature : Feature<BottleTreeFeature.Config>(Config.CODEC) {
	data class Config(val trunk: BlockState, val tips: BlockState, val flowers: BlockState, val height: Span, val girth: Span) : FeatureConfiguration {
		companion object {
			@JvmField
			val CODEC: Codec<Config> = RecordCodecBuilder.create { i ->
				i.group(
					BlockState.CODEC.fieldOf("trunk").forGetter(Config::trunk),
					BlockState.CODEC.fieldOf("tips").forGetter(Config::tips),
					BlockState.CODEC.fieldOf("flowers").forGetter(Config::flowers),
					Span.CODEC.optionalFieldOf("height", Span.of(3.0, 5.0)).forGetter(Config::height),
					Span.CODEC.optionalFieldOf("girth", Span.of(0.9, 1.6)).forGetter(Config::girth),
				).apply(i) { trunk, tips, flowers, height, girth -> Config(trunk, tips, flowers, height, girth) }
			}
		}
	}

	override fun place(context: FeaturePlaceContext<Config>): Boolean {
		val level = context.level()
		val r = context.random()
		val cfg = context.config()
		val o = context.origin()
		val ground = level.getBlockState(o.below())
		if (!io.github.menonng.biomesmod.compat.LandformCompat.isSoil(ground) && !ground.`is`(BlockTags.SAND) && !ground.`is`(BlockTags.BASE_STONE_OVERWORLD)) {
			return false
		}
		val p = BlockPos.MutableBlockPos()
		val h = Math.round(cfg.height.min + r.nextFloat() * (cfg.height.max - cfg.height.min))
		val g = cfg.girth.min + r.nextFloat() * (cfg.girth.max - cfg.girth.min)
		// Swollen base: widest one block up, narrowing to a single block at the fork.
		for (y in 0 until h) {
			val t = y / max(1.0, h - 1.0)
			val rad = g * (if (y == 0) 0.9 else if (y == 1) 1.0 else (1 - t) * 1.05)
			val ri = Math.ceil(rad).toInt()
			for (dz in -ri..ri) {
				for (dx in -ri..ri) {
					if (dx * dx + dz * dz <= rad * rad + 0.35) {
						set(level, p.set(o.x + dx, o.y + y, o.z + dz), cfg.trunk)
					}
				}
			}
			set(level, p.set(o.x, o.y + y, o.z), cfg.trunk)
		}
		val leaves = if (cfg.tips.hasProperty(LeavesBlock.PERSISTENT)) cfg.tips.setValue(LeavesBlock.PERSISTENT, true) else cfg.tips
		val n = 3 + r.nextInt(3)
		val phase = r.nextFloat() * Math.PI * 2
		for (b in 0 until n) {
			val a = phase + Math.PI * 2 * b / n + (r.nextFloat() - 0.5) * 0.6
			val len = 1 + r.nextInt(3)
			var x = o.x.toDouble()
			var z = o.z.toDouble()
			var y = o.y + h - 1
			for (s in 0 until len) {
				x += cos(a) * 0.8
				z += sin(a) * 0.8
				y += 1
				set(level, p.set(Math.round(x).toInt(), y, Math.round(z).toInt()), cfg.trunk)
			}
			val tx = Math.round(x).toInt()
			val tz = Math.round(z).toInt()
			// A tuft of glossy leaves with flowers on top.
			for (dz in -1..1) {
				for (dx in -1..1) {
					if (dx * dx + dz * dz <= 1 || r.nextFloat() < 0.35) {
						set(level, p.set(tx + dx, y + 1, tz + dz), leaves)
					}
				}
			}
			set(level, p.set(tx, y + 2, tz), leaves)
			var fl = cfg.flowers
			if (fl.hasProperty(BlockStateProperties.FLOWER_AMOUNT)) {
				fl = fl.setValue(BlockStateProperties.FLOWER_AMOUNT, 2 + r.nextInt(3))
			}
			if (fl.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
				fl = fl.setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.Plane.HORIZONTAL.getRandomDirection(r))
			}
			if (level.getBlockState(p.set(tx, y + 3, tz)).isAir) {
				level.setBlock(p, fl, 19)
			}
		}
		return true
	}

	private fun set(level: WorldGenLevel, pos: BlockPos, state: BlockState) {
		val cur = level.getBlockState(pos)
		if (cur.isAir || cur.canBeReplaced() || cur.`is`(BlockTags.LEAVES)) {
			level.setBlock(pos, state, 19)
		}
	}
}
