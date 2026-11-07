package io.github.menonng.biomesmod.terrain.landform

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `biomesmod:umbrella_tree`: a tree whose trunk forks into many upward branches carrying one dense, flat-topped
 * umbrella crown (Socotra dragon's blood tree). `twist` > 0 leans and kinks the trunk instead, for flat-crowned savanna
 * trees. Place it with ordinary placement modifiers (count, in_square, heightmap, biome); it only grows on dirt-like
 * ground and never overwrites solid blocks.
 */
class UmbrellaTreeFeature : Feature<UmbrellaTreeFeature.Config>(Config.CODEC) {
	data class Config(
		val trunk: BlockState, val leaves: BlockState, val trunkHeight: Span, val crownRadius: Span, val crownThickness: Span,
		val branches: Int, val twist: Float,
	) : FeatureConfiguration {
		companion object {
			@JvmField
			val CODEC: Codec<Config> = RecordCodecBuilder.create { i ->
				i.group(
					BlockState.CODEC.optionalFieldOf("trunk", B.of("dark_oak_log")).forGetter(Config::trunk),
					BlockState.CODEC.optionalFieldOf("leaves", B.of("dark_oak_leaves")).forGetter(Config::leaves),
					Span.CODEC.optionalFieldOf("trunk_height", Span.of(3.0, 5.0)).forGetter(Config::trunkHeight),
					Span.CODEC.optionalFieldOf("crown_radius", Span.of(3.5, 5.5)).forGetter(Config::crownRadius),
					Span.CODEC.optionalFieldOf("crown_thickness", Span.of(2.0, 3.0)).forGetter(Config::crownThickness),
					Codec.intRange(2, 12).optionalFieldOf("branches", 6).forGetter(Config::branches),
					Codec.floatRange(0f, 1f).optionalFieldOf("twist", 0f).forGetter(Config::twist),
				).apply(i) { trunk, leaves, trunkHeight, crownRadius, crownThickness, branches, twist ->
					Config(trunk, leaves, trunkHeight, crownRadius, crownThickness, branches, twist)
				}
			}
		}
	}

	override fun place(context: FeaturePlaceContext<Config>): Boolean {
		val level = context.level()
		val r = context.random()
		val cfg = context.config()
		val origin = context.origin()
		val ground = level.getBlockState(origin.below())
		if (!ground.`is`(BlockTags.DIRT) && !ground.`is`(BlockTags.SAND)) {
			return false
		}
		val h = Math.round(cfg.trunkHeight.min + r.nextFloat() * (cfg.trunkHeight.max - cfg.trunkHeight.min))
		val cr = (cfg.crownRadius.min + r.nextFloat() * (cfg.crownRadius.max - cfg.crownRadius.min)).toDouble()
		val ct = Math.round(cfg.crownThickness.min + r.nextFloat() * (cfg.crownThickness.max - cfg.crownThickness.min))
		val log = cfg.trunk
		val leaves = cfg.leaves
		val p = BlockPos.MutableBlockPos()

		// Trunk (optionally leaning and kinked).
		var tx = origin.x.toDouble()
		var tz = origin.z.toDouble()
		val lean = r.nextFloat() * Math.PI * 2
		for (y in 0 until h) {
			if (cfg.twist > 0 && y > 0 && r.nextFloat() < cfg.twist * 0.6) {
				tx += cos(lean)
				tz += sin(lean)
			}
			set(level, p.set(Math.round(tx).toInt(), origin.y + y, Math.round(tz).toInt()), log)
		}
		val forkY = origin.y + h
		val crownBase = forkY + max(2, Math.round(cr * 0.6).toInt())
		val cx = Math.round(tx).toInt()
		val cz = Math.round(tz).toInt()

		// Branches: fan out and up from the fork to just under the crown.
		val n = cfg.branches
		val phase = r.nextFloat() * Math.PI * 2
		for (b in 0 until n) {
			val a = phase + Math.PI * 2 * b / n + (r.nextFloat() - 0.5) * 0.5
			val reach = cr * (0.55 + 0.3 * r.nextFloat())
			val steps = max(2, crownBase - forkY)
			for (s in 0..steps) {
				val t = s / steps.toDouble()
				val bx = cx + Math.round(cos(a) * reach * t).toInt()
				val bz = cz + Math.round(sin(a) * reach * t).toInt()
				set(level, p.set(bx, forkY + s - 1, bz), log)
			}
		}

		// Crown: a dense, flat-topped dome, slightly ragged at the rim.
		val ri = ceil(cr).toInt()
		for (dy in 0 until ct) {
			val layerR = cr * (if (dy == ct - 1) 0.85 else 1.0)
			for (dz in -ri..ri) {
				for (dx in -ri..ri) {
					val d = sqrt((dx * dx + dz * dz).toDouble())
					if (d > layerR + (r.nextFloat() - 0.5) * 0.8) {
						continue
					}
					set(level, p.set(cx + dx, crownBase + dy, cz + dz), leaves)
				}
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
