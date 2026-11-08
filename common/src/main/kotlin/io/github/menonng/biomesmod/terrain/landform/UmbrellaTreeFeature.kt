package io.github.menonng.biomesmod.terrain.landform

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
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
		val branches: Int, val twist: Float, val forks: Int, val thick: Float,
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
					Codec.intRange(0, 3).optionalFieldOf("forks", 0).forGetter(Config::forks),
					Codec.floatRange(0f, 1f).optionalFieldOf("thick", 0f).forGetter(Config::thick),
				).apply(i) { trunk, leaves, trunkHeight, crownRadius, crownThickness, branches, twist, forks, thick ->
					Config(trunk, leaves, trunkHeight, crownRadius, crownThickness, branches, twist, forks, thick)
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
		if (!io.github.menonng.biomesmod.compat.LandformCompat.isSoil(ground) && !ground.`is`(BlockTags.SAND)) {
			return false
		}
		val h = Math.round(cfg.trunkHeight.min + r.nextFloat() * (cfg.trunkHeight.max - cfg.trunkHeight.min))
		val cr = (cfg.crownRadius.min + r.nextFloat() * (cfg.crownRadius.max - cfg.crownRadius.min)).toDouble()
		val ct = Math.round(cfg.crownThickness.min + r.nextFloat() * (cfg.crownThickness.max - cfg.crownThickness.min))
		val log = cfg.trunk
		val leaves = if (cfg.leaves.hasProperty(LeavesBlock.PERSISTENT)) cfg.leaves.setValue(LeavesBlock.PERSISTENT, true) else cfg.leaves
		val p = BlockPos.MutableBlockPos()
		if (cfg.forks > 0) {
			return dichotomous(level, r, origin, cfg, h.toInt(), cr, ct.toInt(), log, leaves)
		}

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

	/**
	 * Dragon's blood form: a stout trunk that forks again and again into pairs of upturned branches, each tip carrying
	 * a rosette cluster, all packed into one dense umbrella crown (domed on top, slightly cupped below).
	 */
	private fun dichotomous(level: WorldGenLevel, r: RandomSource, origin: BlockPos, cfg: Config, h: Int, cr: Double, ct: Int,
		log: BlockState, leaves: BlockState): Boolean {
		val p = BlockPos.MutableBlockPos()
		val thick = r.nextFloat() < cfg.thick
		for (y in 0 until h) {
			set(level, p.set(origin.x, origin.y + y, origin.z), log)
			if (thick && y < h - 1) {
				set(level, p.set(origin.x + 1, origin.y + y, origin.z), log)
				set(level, p.set(origin.x, origin.y + y, origin.z + 1), log)
				set(level, p.set(origin.x + 1, origin.y + y, origin.z + 1), log)
			}
		}
		val ox = origin.x + (if (thick) 0.5 else 0.0)
		val oz = origin.z + (if (thick) 0.5 else 0.0)
		val tips = ArrayList<DoubleArray>()
		val n = cfg.branches.coerceIn(2, 5)
		val phase = r.nextFloat() * Math.PI * 2
		// Total horizontal reach of a branch path ~ 0.8 crown radius; lengths shrink by 0.75 per fork.
		var sum = 0.0
		var f = 1.0
		for (k in 0..cfg.forks) {
			sum += f
			f *= 0.75
		}
		val len0 = cr * 0.8 / (sum * 0.62)
		for (b in 0 until n) {
			val a = phase + Math.PI * 2 * b / n + (r.nextFloat() - 0.5) * 0.4
			branch(level, r, p, ox, origin.y + h - 0.5, oz, a, 0.85 + r.nextFloat() * 0.2, len0, cfg.forks, log, tips)
		}
		var top = origin.y + h
		for (t in tips) {
			top = max(top, t[1].toInt())
		}
		val crownTop = top + ct
		val ri = ceil(cr + 1).toInt()
		val cx = ox
		val cz = oz
		// Umbrella: domed top, cupped underside, ragged rim.
		for (dz in -ri..ri) {
			for (dx in -ri..ri) {
				val d = sqrt((dx + 0.5 + origin.x - cx - 0.5).let { it * it } + (dz + 0.5 + origin.z - cz - 0.5).let { it * it })
				val rim = cr * (0.92 + 0.16 * r.nextFloat())
				if (d > rim) {
					continue
				}
				val k = d / cr
				val yTop = crownTop - Math.round(1.6 * k * k).toInt()
				val yBot = top - 1 + Math.round(1.4 * k * k).toInt() - (if (k > 0.85) 0 else 1)
				for (y in yBot..yTop) {
					set(level, p.set(origin.x + dx, y, origin.z + dz), leaves)
				}
			}
		}
		// Rosettes at the tips bulge out of the crown surface.
		for (t in tips) {
			for (dy in 0..1) {
				for (dz in -1..1) {
					for (dx in -1..1) {
						if (abs(dx) + abs(dz) + dy <= 2 && r.nextFloat() < 0.85) {
							set(level, p.set(floor(t[0]).toInt() + dx, t[1].toInt() + 1 + dy + ct - 1, floor(t[2]).toInt() + dz), leaves)
						}
					}
				}
			}
		}
		return true
	}

	private fun branch(level: WorldGenLevel, r: RandomSource, p: BlockPos.MutableBlockPos, x: Double, y: Double, z: Double, a: Double,
		elev: Double, len: Double, depth: Int, log: BlockState, tips: MutableList<DoubleArray>) {
		val dx = cos(a) * cos(elev)
		val dy = sin(elev)
		val dz = sin(a) * cos(elev)
		val steps = max(1, Math.round(len).toInt())
		var px = x
		var py = y
		var pz = z
		for (s in 0 until steps) {
			px += dx
			py += dy
			pz += dz
			set(level, p.set(floor(px).toInt(), floor(py).toInt(), floor(pz).toInt()), log)
		}
		if (depth <= 0) {
			tips.add(doubleArrayOf(px, py, pz))
			return
		}
		val spread = 0.45 + r.nextFloat() * 0.25
		for (k in 0 until 2) {
			val na = a + (if (k == 0) spread else -spread) + (r.nextFloat() - 0.5) * 0.3
			branch(level, r, p, px, py, pz, na, (elev * 0.85).coerceAtLeast(0.45), len * 0.75, depth - 1, log, tips)
		}
	}

	private fun set(level: WorldGenLevel, pos: BlockPos, state: BlockState) {
		val cur = level.getBlockState(pos)
		if (cur.isAir || cur.canBeReplaced() || cur.`is`(BlockTags.LEAVES)) {
			level.setBlock(pos, state, 19)
		}
	}
}
