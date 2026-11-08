package io.github.menonng.biomesmod.terrain.landform

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.menonng.biomesmod.terrain.util.StrataPalette
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.AmethystClusterBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `biomesmod:rock_swap`: re-dresses the ground of one biome: in every column of the origin's biome, blocks from `from`
 * within `depth` blocks of the top (and on every exposed face down to `depth`) become a clustered mix of `to`. Used
 * where surface rules do not reach (the End). Place once per chunk at the surface (count 1, heightmap).
 */
class RockSwapFeature : Feature<RockSwapFeature.Config>(Config.CODEC) {
	data class Config(val from: List<BlockState>, val to: List<StrataPalette.Weighted>, val depth: Int, val top: List<StrataPalette.Weighted>,
		val topCoverage: Float, val biome: java.util.Optional<ResourceKey<Biome>>) : FeatureConfiguration {
		companion object {
			@JvmField
			val CODEC: Codec<Config> = RecordCodecBuilder.create { i ->
				i.group(
					BlockState.CODEC.listOf().fieldOf("from").forGetter(Config::from),
					StrataPalette.Weighted.CODEC.listOf().fieldOf("to").forGetter(Config::to),
					Codec.intRange(1, 256).optionalFieldOf("depth", 64).forGetter(Config::depth),
					StrataPalette.Weighted.CODEC.listOf().optionalFieldOf("top", listOf()).forGetter(Config::top),
					Codec.floatRange(0f, 1f).optionalFieldOf("top_coverage", 0.3f).forGetter(Config::topCoverage),
					ResourceKey.codec(Registries.BIOME).optionalFieldOf("biome").forGetter(Config::biome),
				).apply(i) { from, to, depth, top, cov, biome -> Config(from, to, depth, top, cov, biome) }
			}
		}
	}

	override fun place(context: FeaturePlaceContext<Config>): Boolean {
		val level = context.level()
		val cfg = context.config()
		val o = context.origin()
		val x0 = o.x and 15.inv()
		val z0 = o.z and 15.inv()
		// With "biome" set, every column of that biome in the chunk is dressed (place without a biome filter, so the edge
		// follows the biome, not the chunk grid); otherwise the origin's biome.
		val key = cfg.biome.orElse(null)
		val biome = level.getBiome(o)
		val from = cfg.from.map { it.block }.toSet()
		val layer = StrataPalette.Layer(cfg.to, 1)
		val topLayer = if (cfg.top.isEmpty()) null else StrataPalette.Layer(cfg.top, 1)
		val p = BlockPos.MutableBlockPos()
		var any = false
		for (dz in 0 until 16) {
			for (dx in 0 until 16) {
				val x = x0 + dx
				val z = z0 + dz
				val top = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1
				val here = level.getBiome(p.set(x, top, z))
				if (if (key != null) !here.`is`(key) else here != biome) {
					continue
				}
				for (y in top downTo top - cfg.depth) {
					val s = level.getBlockState(p.set(x, y, z))
					if (y == top && topLayer != null && s.block in from &&
						StrataPalette.valueNoise(x / 9.0, 0.0, z / 9.0, 0x5B3) < cfg.topCoverage) {
						level.setBlock(p, topLayer.pick(StrataPalette.hash(x, y, z, 0x5B4)), 2)
						any = true
						continue
					}
					if (s.block in from) {
						val u = 0.62 * StrataPalette.valueNoise(x / 5.0, y / 5.0, z / 5.0, 0x5B1) + 0.38 * StrataPalette.hash(x, y, z, 0x5B2)
						level.setBlock(p, layer.pick(u), 2)
						any = true
					}
				}
			}
		}
		return any
	}
}

/**
 * `biomesmod:crystal_cluster`: a burst of giant crystal prisms growing out of the ground at angles, each with a
 * six-sided body that tapers to a point, set in a collar of base rock, with small crystal buds scattered at their feet.
 */
class CrystalClusterFeature : Feature<CrystalClusterFeature.Config>(Config.CODEC) {
	data class Config(val crystal: BlockState, val base: BlockState, val bud: BlockState, val count: Span, val length: Span, val radius: Span) :
		FeatureConfiguration {
		companion object {
			@JvmField
			val CODEC: Codec<Config> = RecordCodecBuilder.create { i ->
				i.group(
					BlockState.CODEC.fieldOf("crystal").forGetter(Config::crystal),
					BlockState.CODEC.fieldOf("base").forGetter(Config::base),
					BlockState.CODEC.fieldOf("bud").forGetter(Config::bud),
					Span.CODEC.optionalFieldOf("count", Span.of(4.0, 9.0)).forGetter(Config::count),
					Span.CODEC.optionalFieldOf("length", Span.of(6.0, 20.0)).forGetter(Config::length),
					Span.CODEC.optionalFieldOf("radius", Span.of(1.0, 2.4)).forGetter(Config::radius),
				).apply(i) { crystal, base, bud, count, length, radius -> Config(crystal, base, bud, count, length, radius) }
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
		val p = BlockPos.MutableBlockPos()
		val n = (cfg.count.min + r.nextFloat() * (cfg.count.max - cfg.count.min)).toInt()
		for (k in 0 until n) {
			val a = r.nextFloat() * Math.PI * 2
			val tilt = if (k == 0) r.nextFloat() * 0.25 else 0.25 + r.nextFloat() * 0.6
			val len = (cfg.length.min + r.nextFloat() * (cfg.length.max - cfg.length.min)) * (if (k == 0) 1.0 else 0.55 + 0.4 * r.nextFloat())
			val rad = (cfg.radius.min + r.nextFloat() * (cfg.radius.max - cfg.radius.min)) * (if (k == 0) 1.0 else 0.75)
			val dx = sin(tilt) * cos(a)
			val dy = cos(tilt)
			val dz = sin(tilt) * sin(a)
			val sx = o.x + 0.5 + cos(a) * rad * 0.8
			val sz = o.z + 0.5 + sin(a) * rad * 0.8
			val sy = o.y - 2.0
			val ri = ceil(rad + 1).toInt()
			val steps = ceil(len * 1.6).toInt()
			for (s in 0..steps) {
				val t = s / steps.toDouble()
				val cx = sx + dx * len * t
				val cy = sy + dy * len * t
				val cz = sz + dz * len * t
				val rr = if (t < 0.7) rad else rad * (1 - (t - 0.7) / 0.3)
				run {
					val oy = 0
					for (oz in -ri..ri) {
						for (ox in -ri..ri) {
							// Hexagonal cross-section: max of the three axial distances in the plane.
							val hx = ox.toDouble()
							val hz = oz.toDouble()
							val hex = max(max(Math.abs(hx), Math.abs(hx * 0.5 + hz * 0.866)), Math.abs(-hx * 0.5 + hz * 0.866))
							if (hex <= rr + 0.2) {
								val pos = p.set(floor(cx).toInt() + ox, floor(cy).toInt() + oy, floor(cz).toInt() + oz)
								val cur = level.getBlockState(pos)
								if (cur.isAir || cur.canBeReplaced() || pos.y < o.y) {
									level.setBlock(pos, cfg.crystal, 2)
								}
							}
						}
					}
				}
			}
		}
		// Collar of base rock and a scatter of buds.
		for (dz in -4..4) {
			for (dx in -4..4) {
				val d = sqrt((dx * dx + dz * dz).toDouble())
				if (d > 4.2) {
					continue
				}
				val y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, o.x + dx, o.z + dz)
				val below = level.getBlockState(p.set(o.x + dx, y - 1, o.z + dz))
				if (below.isAir || below == cfg.crystal) {
					continue
				}
				if (d < 3 && r.nextFloat() < 0.6) {
					level.setBlock(p, cfg.base, 2)
				}
				if (r.nextFloat() < 0.3 && level.getBlockState(p.set(o.x + dx, y, o.z + dz)).isAir) {
					val bud = if (cfg.bud.hasProperty(AmethystClusterBlock.FACING)) cfg.bud.setValue(AmethystClusterBlock.FACING, Direction.UP) else cfg.bud
					level.setBlock(p, bud, 2)
				}
			}
		}
		return true
	}
}
