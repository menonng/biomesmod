package io.github.menonng.biomesmod.terrain.landform

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.menonng.biomesmod.terrain.util.StrataPalette
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.AmethystClusterBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

private fun facing(state: BlockState, dir: Direction): BlockState =
	if (state.hasProperty(AmethystClusterBlock.FACING)) state.setValue(AmethystClusterBlock.FACING, dir) else state

/**
 * `biomesmod:floating_isle`: a small island hanging in the air above the ground (an inverted, lobed cone of rock with a
 * dusty top), flowers and crystal buds on top, glowing roots and ceiling flowers underneath. Under its centre, on the
 * ground, a star spring may rise: step into it and it carries you up.
 */
class FloatingIsleFeature : Feature<FloatingIsleFeature.Config>(Config.CODEC) {
	data class Config(
		val rock: List<StrataPalette.Weighted>, val top: BlockState, val flower: BlockState, val bud: BlockState, val roots: BlockState,
		val spring: BlockState, val springChance: Float, val radius: Span, val lift: Span,
	) : FeatureConfiguration {
		companion object {
			@JvmField
			val CODEC: Codec<Config> = RecordCodecBuilder.create { i ->
				i.group(
					StrataPalette.Weighted.CODEC.listOf().fieldOf("rock").forGetter(Config::rock),
					BlockState.CODEC.fieldOf("top").forGetter(Config::top),
					BlockState.CODEC.fieldOf("flower").forGetter(Config::flower),
					BlockState.CODEC.fieldOf("bud").forGetter(Config::bud),
					BlockState.CODEC.fieldOf("roots").forGetter(Config::roots),
					BlockState.CODEC.fieldOf("spring").forGetter(Config::spring),
					Codec.floatRange(0f, 1f).optionalFieldOf("spring_chance", 0.6f).forGetter(Config::springChance),
					Span.CODEC.optionalFieldOf("radius", Span.of(3.0, 8.0)).forGetter(Config::radius),
					Span.CODEC.optionalFieldOf("lift", Span.of(12.0, 24.0)).forGetter(Config::lift),
				).apply(i) { rock, top, flower, bud, roots, spring, sc, radius, lift -> Config(rock, top, flower, bud, roots, spring, sc, radius, lift) }
			}
		}
	}

	override fun place(context: FeaturePlaceContext<Config>): Boolean {
		val level = context.level()
		val r = context.random()
		val cfg = context.config()
		val o = context.origin()
		if (level.getBlockState(o.below()).isAir) {
			return false
		}
		val rad = cfg.radius.min + r.nextFloat() * (cfg.radius.max - cfg.radius.min)
		val topY = o.y + (cfg.lift.min + r.nextFloat() * (cfg.lift.max - cfg.lift.min)).toInt()
		if (topY + 4 >= level.maxY()) {
			return false
		}
		val rock = StrataPalette.Layer(cfg.rock, 1)
		val p = BlockPos.MutableBlockPos()
		val ri = ceil(rad * 1.25).toInt()
		val seed = r.nextLong()
		for (dz in -ri..ri) {
			for (dx in -ri..ri) {
				val a = atan2(dz.toDouble(), dx.toDouble())
				val lobe = rad * (1 + 0.22 * LNoise.angular(seed, a, 4.0))
				val d = sqrt((dx * dx + dz * dz).toDouble())
				if (d > lobe) {
					continue
				}
				val k = 1 - d / lobe
				val dome = (k * 1.6).toInt()
				val depth = (k * lobe * 1.5 + 1.5 * LNoise.noise2(seed xor 3, dx / 2.5, dz / 2.5)).toInt().coerceAtLeast(1)
				val x = o.x + dx
				val z = o.z + dz
				val surface = topY + dome
				for (y in surface - depth..surface) {
					val st = if (y == surface) cfg.top else rock.pick(LNoise.unit(seed, x, y, z))
					if (level.getBlockState(p.set(x, y, z)).isAir) {
						level.setBlock(p, st, 2)
					}
				}
				// Top: flowers and crystal buds; underside: hanging roots and ceiling flowers.
				val roll = r.nextFloat()
				if (level.getBlockState(p.set(x, surface + 1, z)).isAir) {
					if (roll < 0.12) level.setBlock(p, facing(cfg.flower, Direction.UP), 2)
					else if (roll < 0.17) level.setBlock(p, facing(cfg.bud, Direction.UP), 2)
				}
				val under = surface - depth - 1
				if (level.getBlockState(p.set(x, under, z)).isAir) {
					val u = r.nextFloat()
					if (u < 0.28) level.setBlock(p, cfg.roots, 2)
					else if (u < 0.36) level.setBlock(p, facing(cfg.flower, Direction.DOWN), 2)
				}
			}
		}
		// The spring that reaches it.
		if (r.nextFloat() < cfg.springChance) {
			val gy = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, o.x, o.z) - 1
			if (gy > level.minY() && !level.getBlockState(p.set(o.x, gy, o.z)).isAir && gy < topY - 6) {
				level.setBlock(p, cfg.spring, 2)
			}
		}
		return true
	}
}

/**
 * `biomesmod:abyss_well`: a shaft punched straight through the island into the void below. Its walls glow with starlit
 * stone, flowers grow out of them into the shaft and roots hang from every overhang; crystal buds ring the rim.
 */
class AbyssWellFeature : Feature<AbyssWellFeature.Config>(Config.CODEC) {
	data class Config(val wall: BlockState, val wallChance: Float, val flower: BlockState, val roots: BlockState, val bud: BlockState, val radius: Span) :
		FeatureConfiguration {
		companion object {
			@JvmField
			val CODEC: Codec<Config> = RecordCodecBuilder.create { i ->
				i.group(
					BlockState.CODEC.fieldOf("wall").forGetter(Config::wall),
					Codec.floatRange(0f, 1f).optionalFieldOf("wall_chance", 0.35f).forGetter(Config::wallChance),
					BlockState.CODEC.fieldOf("flower").forGetter(Config::flower),
					BlockState.CODEC.fieldOf("roots").forGetter(Config::roots),
					BlockState.CODEC.fieldOf("bud").forGetter(Config::bud),
					Span.CODEC.optionalFieldOf("radius", Span.of(3.0, 6.0)).forGetter(Config::radius),
				).apply(i) { wall, wc, flower, roots, bud, radius -> Config(wall, wc, flower, roots, bud, radius) }
			}
		}
	}

	override fun place(context: FeaturePlaceContext<Config>): Boolean {
		val level = context.level()
		val r = context.random()
		val cfg = context.config()
		val o = context.origin()
		val rad = cfg.radius.min + r.nextFloat() * (cfg.radius.max - cfg.radius.min)
		val p = BlockPos.MutableBlockPos()
		val seed = r.nextLong()
		val ri = ceil(rad + 2).toInt()
		// Only where the island is thick enough to make a shaft (not over open void).
		var solid = 0
		for (y in o.y - 1 downTo max(level.minY(), o.y - 40)) {
			if (!level.getBlockState(p.set(o.x, y, o.z)).isAir) solid++
		}
		if (solid < 8) {
			return false
		}
		val bottom = level.minY() + 1
		for (y in o.y + 1 downTo bottom) {
			val wob = 0.8 * LNoise.noise1(seed, y / 7.0)
			for (dz in -ri..ri) {
				for (dx in -ri..ri) {
					val d = sqrt((dx * dx + dz * dz).toDouble())
					val rr = rad + wob + 0.5 * LNoise.noise2(seed xor 5, (dx + y) / 3.0, dz / 3.0)
					val x = o.x + dx
					val z = o.z + dz
					val cur = level.getBlockState(p.set(x, y, z))
					if (d < rr) {
						if (!cur.isAir) level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2)
					} else if (d < rr + 1.2 && !cur.isAir && r.nextFloat() < cfg.wallChance) {
						level.setBlock(p, cfg.wall, 2)
					}
				}
			}
		}
		// Dress the walls: flowers grow into the shaft, roots hang from overhangs, buds on the rim.
		for (y in o.y downTo bottom) {
			for (dz in -ri..ri) {
				for (dx in -ri..ri) {
					val x = o.x + dx
					val z = o.z + dz
					if (!level.getBlockState(p.set(x, y, z)).isAir) continue
					for (dir in Direction.Plane.HORIZONTAL) {
						val wall = level.getBlockState(p.set(x - dir.stepX, y, z - dir.stepZ))
						if (!wall.isAir && wall.canOcclude() && r.nextFloat() < 0.05) {
							level.setBlock(p.set(x, y, z), facing(cfg.flower, dir), 2)
							break
						}
					}
					p.set(x, y, z)
					if (level.getBlockState(p).isAir && !level.getBlockState(p.set(x, y + 1, z)).isAir && level.getBlockState(p).canOcclude() &&
						r.nextFloat() < 0.3) {
						level.setBlock(p.set(x, y, z), cfg.roots, 2)
					}
				}
			}
		}
		for (k in 0 until 10) {
			val a = r.nextFloat() * Math.PI * 2
			val x = floor(o.x + 0.5 + Math.cos(a) * (rad + 1.5)).toInt()
			val z = floor(o.z + 0.5 + Math.sin(a) * (rad + 1.5)).toInt()
			val y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z)
			if (y > o.y - 4 && level.getBlockState(p.set(x, y, z)).isAir && !level.getBlockState(p.set(x, y - 1, z)).isAir) {
				level.setBlock(p.set(x, y, z), facing(cfg.bud, Direction.UP), 2)
			}
		}
		return true
	}
}

private fun WorldGenLevel.maxY(): Int = io.github.menonng.biomesmod.compat.LandformCompat.maxY(this)

private fun WorldGenLevel.minY(): Int = io.github.menonng.biomesmod.compat.LevelCompat.minY(this)
