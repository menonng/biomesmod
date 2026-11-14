package io.github.menonng.biomesmod.terrain.landform

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Shared block writing for the procedural trees and rocks. */
private class Pen(val level: WorldGenLevel, val r: RandomSource) {
	val p = BlockPos.MutableBlockPos()

	fun get(x: Int, y: Int, z: Int): BlockState = level.getBlockState(p.set(x, y, z))

	/** Free for a tree or a plant to grow into: air, snow layers, grass and the like. */
	fun free(x: Int, y: Int, z: Int): Boolean {
		val s = get(x, y, z)
		return s.isAir || (!s.blocksMotion() && s.fluidState.isEmpty) || s.`is`(BlockTags.LEAVES)
	}

	fun set(x: Int, y: Int, z: Int, s: BlockState) {
		level.setBlock(p.set(x, y, z), s, 2)
	}

	fun log(x: Int, y: Int, z: Int, log: BlockState, axis: Direction.Axis = Direction.Axis.Y) {
		val s = get(x, y, z)
		if (s.isAir || !s.blocksMotion() || s.`is`(BlockTags.LEAVES)) {
			set(x, y, z, if (log.hasProperty(RotatedPillarBlock.AXIS)) log.setValue(RotatedPillarBlock.AXIS, axis) else log)
		}
	}

	fun leaf(x: Int, y: Int, z: Int, leaves: BlockState) {
		val s = get(x, y, z)
		if (s.isAir || (!s.blocksMotion() && s.fluidState.isEmpty && !s.`is`(BlockTags.LEAVES))) {
			set(x, y, z, leaves)
		}
	}

	/** A ragged ellipsoid of leaves: radii [rx] (x and z) and [ry]. */
	fun blob(cx: Double, cy: Double, cz: Double, rx: Double, ry: Double, leaves: BlockState, sx: Double = 1.0, sz: Double = 1.0) {
		val ix = (rx * max(sx, sz)).toInt() + 1
		val iy = ry.toInt() + 1
		for (dx in -ix..ix) {
			for (dz in -ix..ix) {
				for (dy in -iy..iy) {
					val x = (cx + dx).roundToInt()
					val y = (cy + dy).roundToInt()
					val z = (cz + dz).roundToInt()
					val ex = (x - cx) / (rx * sx)
					val ey = (y - cy) / ry
					val ez = (z - cz) / (rx * sz)
					val q = ex * ex + ey * ey + ez * ez
					if (q <= 1.0 && (q < 0.6 || r.nextFloat() < 0.72)) {
						leaf(x, y, z, leaves)
					}
				}
			}
		}
	}
}

/**
 * `biomesmod:wild_tree`: trees grown block by block, so that no two are alike. `style` picks the growth form, `log`
 * and `leaves` the species, `height` the range of trunk heights:
 *
 * * `conifer`: a tapering spire of whorled branches, the lower trunk bare on some.
 * * `columnar`: a narrow column (cypress, lodgepole, poplar).
 * * `krummholz`: a stunted tree bent by the prevailing wind, all its foliage streaming to leeward.
 * * `broadleaf`: a trunk forking into limbs, each carrying its own crown.
 * * `palm`: a curved bare trunk with a head of arching fronds.
 * * `flat_top`: a leaning, forking trunk under flat plates of foliage (thorn trees, junipers).
 * * `shrub`: a low many-stemmed bush.
 */
class WildTreeFeature : Feature<WildTreeFeature.Config>(Config.CODEC) {
	data class Config(val style: String, val log: BlockState, val leaves: BlockState, val height: Span) : FeatureConfiguration {
		companion object {
			@JvmField
			val CODEC: Codec<Config> = RecordCodecBuilder.create { i ->
				i.group(
					Codec.STRING.fieldOf("style").forGetter(Config::style),
					BlockState.CODEC.fieldOf("log").forGetter(Config::log),
					BlockState.CODEC.fieldOf("leaves").forGetter(Config::leaves),
					Span.CODEC.optionalFieldOf("height", Span.of(5.0, 9.0)).forGetter(Config::height),
				).apply(i) { style, log, leaves, height -> Config(style, log, leaves, height) }
			}
		}
	}

	override fun place(context: FeaturePlaceContext<Config>): Boolean {
		val level = context.level()
		val r = context.random()
		val cfg = context.config()
		val o = context.origin()
		val pen = Pen(level, r)
		val below = level.getBlockState(o.below())
		val soil = SOIL.any { below.`is`(it) }
		val dry = below.`is`(BlockTags.SAND) || below.`is`(BlockTags.TERRACOTTA) || below.`is`(Blocks.GRAVEL)
		val ok = when (cfg.style) {
			"palm", "flat_top", "shrub" -> soil || dry
			"conifer", "krummholz" -> soil || below.`is`(Blocks.SNOW_BLOCK) || below.`is`(Blocks.STONE)
			else -> soil
		}
		if (!ok || !pen.free(o.x, o.y, o.z) || !pen.free(o.x, o.y + 2, o.z)) {
			return false
		}
		val leaves = if (cfg.leaves.hasProperty(LeavesBlock.PERSISTENT)) cfg.leaves.setValue(LeavesBlock.PERSISTENT, true) else cfg.leaves
		val log = cfg.log
		val h = max(1, (cfg.height.min + r.nextFloat() * (cfg.height.max - cfg.height.min + 0.99f)).toInt())
		val x = o.x
		val y = o.y
		val z = o.z
		when (cfg.style) {
			"conifer" -> {
				for (i in 0 until h) pen.log(x, y + i, z, log)
				val start = max(1, (h * (0.15 + r.nextFloat() * 0.4)).toInt())
				val rmax = min(3.4, 1.4 + h * 0.16 + r.nextFloat() * 0.6)
				for (i in start..h + 1) {
					val t = (i - start) / (h + 1.0 - start)
					var rad = rmax * (1 - t).pow(0.75)
					if ((i - start) % 2 == 1) rad *= 0.6 // whorls
					if (i > h) rad = 0.0
					val ir = rad.toInt() + 1
					for (dx in -ir..ir) {
						for (dz in -ir..ir) {
							val d = sqrt((dx * dx + dz * dz).toDouble())
							if (d <= rad + 0.3 && (d < rad - 0.6 || r.nextFloat() < 0.75)) pen.leaf(x + dx, y + i, z + dz, leaves)
						}
					}
				}
				pen.leaf(x, y + h, z, leaves)
				pen.leaf(x, y + h + 1, z, leaves)
			}
			"columnar" -> {
				for (i in 0 until h) pen.log(x, y + i, z, log)
				val bulge = 1 + r.nextInt(max(1, h - 2))
				for (i in 1 + r.nextInt(2)..h + 1) {
					val wide = abs(i - bulge) <= 1 && h > 5
					for (dx in -1..1) {
						for (dz in -1..1) {
							if (i > h && (dx != 0 || dz != 0)) continue
							if (dx != 0 && dz != 0 && !(wide && r.nextBoolean())) continue
							pen.leaf(x + dx, y + i, z + dz, leaves)
						}
					}
				}
				pen.leaf(x, y + h + 2, z, leaves)
			}
			"krummholz" -> {
				// One prevailing wind for the whole world, so every flag tree points the same way.
				val wa = ((level.seed xor 0x5EED) and 1023) / 1023.0 * Math.PI * 2 + (r.nextDouble() - 0.5) * 0.5
				val wx = cos(wa)
				val wz = sin(wa)
				var fx = 0.0
				var fz = 0.0
				for (i in 0 until h) {
					pen.log(x + fx.roundToInt(), y + i, z + fz.roundToInt(), log)
					if (i >= 1) {
						fx += wx * (0.35 + r.nextFloat() * 0.35)
						fz += wz * (0.35 + r.nextFloat() * 0.35)
					}
				}
				val len = 1.6 + h * 0.3 + r.nextFloat()
				for (k in 0..2) {
					val t = k / 2.0
					pen.blob(x + fx + wx * len * t, y + h - 0.2 - t * (0.5 + r.nextFloat()), z + fz + wz * len * t, 1.9 - 0.5 * t, 1.1, leaves)
				}
			}
			"broadleaf" -> {
				for (i in 0 until h) pen.log(x, y + i, z, log)
				val limbs = 2 + r.nextInt(3)
				val a0 = r.nextDouble() * Math.PI * 2
				for (k in 0 until limbs) {
					val a = a0 + k * Math.PI * 2 / limbs + (r.nextDouble() - 0.5) * 0.8
					val len = 2 + r.nextInt(3)
					var by = y + h - 2 - r.nextInt(max(1, h / 3))
					var bx = x.toDouble()
					var bz = z.toDouble()
					for (s in 0 until len) {
						bx += cos(a)
						bz += sin(a)
						if (r.nextFloat() < 0.55) by++
						pen.log(bx.roundToInt(), by, bz.roundToInt(), log, if (abs(cos(a)) > abs(sin(a))) Direction.Axis.X else Direction.Axis.Z)
					}
					pen.blob(bx, by + 1.0, bz, 2.0 + r.nextFloat() * 1.2, 1.5 + r.nextFloat() * 0.6, leaves)
				}
				pen.blob(x.toDouble(), y + h + 0.5, z.toDouble(), 2.3 + r.nextFloat() * 1.3, 1.8 + r.nextFloat() * 0.6, leaves)
			}
			"palm" -> {
				val a = r.nextDouble() * Math.PI * 2
				val bend = r.nextFloat() * 3.2
				var tx = x
				var tz = z
				for (i in 0 until h) {
					val t = i / h.toDouble()
					tx = x + (cos(a) * bend * t * t).roundToInt()
					tz = z + (sin(a) * bend * t * t).roundToInt()
					pen.log(tx, y + i, tz, log)
				}
				val top = y + h
				pen.leaf(tx, top, tz, leaves)
				for (k in 0 until 8) {
					val fa = k * Math.PI / 4 + (r.nextDouble() - 0.5) * 0.3
					val len = if (k % 2 == 0) 3 + r.nextInt(2) else 2 + r.nextInt(2)
					for (s in 1..len) {
						val drop = if (s <= 1) 0 else ((s - 1) * (s - 1) * 0.3).roundToInt()
						pen.leaf(tx + (cos(fa) * s).roundToInt(), top - drop, tz + (sin(fa) * s).roundToInt(), leaves)
					}
				}
			}
			"flat_top" -> {
				val a = r.nextDouble() * Math.PI * 2
				var fx = 0.0
				var fz = 0.0
				for (i in 0 until h) {
					pen.log(x + fx.roundToInt(), y + i, z + fz.roundToInt(), log)
					if (i >= h / 2) {
						fx += cos(a) * 0.5
						fz += sin(a) * 0.5
					}
				}
				val forks = 2 + r.nextInt(2)
				for (k in 0 until forks) {
					val fa = a + (k - (forks - 1) / 2.0) * 1.7 + (r.nextDouble() - 0.5) * 0.5
					var bx = x + fx
					var bz = z + fz
					var by = y + h - 1
					val len = 2 + r.nextInt(2)
					for (s in 0 until len) {
						bx += cos(fa)
						bz += sin(fa)
						if (s % 2 == 0) by++
						pen.log(bx.roundToInt(), by, bz.roundToInt(), log)
					}
					pen.blob(bx, by + 1.0, bz, 2.4 + r.nextFloat() * 1.2, 0.6, leaves)
					if (r.nextBoolean()) pen.blob(bx, by + 2.0, bz, 1.4, 0.5, leaves)
				}
			}
			"shrub" -> {
				val stems = 1 + r.nextInt(3)
				for (k in 0 until stems) {
					val sx = x + (if (k == 0) 0 else r.nextInt(3) - 1)
					val sz = z + (if (k == 0) 0 else r.nextInt(3) - 1)
					if (!pen.get(sx, y - 1, sz).blocksMotion() || !pen.free(sx, y, sz)) continue
					val sh = min(h, 1 + r.nextInt(2))
					for (i in 0 until sh) pen.log(sx, y + i, sz, log)
					pen.blob(sx.toDouble(), y + sh.toDouble() - 0.3, sz.toDouble(), 1.2 + r.nextFloat() * 0.9, 0.9 + r.nextFloat() * 0.5, leaves)
				}
			}
			else -> return false
		}
		return true
	}

	companion object {
		private val SOIL = arrayOf(Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.PODZOL, Blocks.ROOTED_DIRT, Blocks.MOSS_BLOCK, Blocks.MUD,
			Blocks.MYCELIUM)
	}
}

/**
 * `biomesmod:wild_rock`: the small natural forms that stand on the ground, each one shaped at random from `blocks`
 * (the first block is the main one) at a scale of `size`:
 *
 * * `boulder`: a lumpy block half sunk in the ground (erratics, rockfall, seracs when made of ice).
 * * `outcrop`: tilted beds of rock breaking the surface, a ramp on one side and a step on the other.
 * * `spire`: a needle or a hoodoo with a wider cap.
 * * `mound`: a low dome (tufa, salt, termite mounds, ice hummocks).
 * * `arch`: a small natural arch.
 * * `driftwood`: a bleached trunk lying on the ground with the stub of a limb (`blocks[0]` is a log).
 */
class WildRockFeature : Feature<WildRockFeature.Config>(Config.CODEC) {
	data class Config(val kind: String, val blocks: List<BlockState>, val size: Span) : FeatureConfiguration {
		companion object {
			@JvmField
			val CODEC: Codec<Config> = RecordCodecBuilder.create { i ->
				i.group(
					Codec.STRING.fieldOf("kind").forGetter(Config::kind),
					BlockState.CODEC.listOf().fieldOf("blocks").forGetter(Config::blocks),
					Span.CODEC.optionalFieldOf("size", Span.of(1.5, 3.0)).forGetter(Config::size),
				).apply(i) { kind, blocks, size -> Config(kind, blocks, size) }
			}
		}
	}

	override fun place(context: FeaturePlaceContext<Config>): Boolean {
		val level = context.level()
		val r = context.random()
		val cfg = context.config()
		val o = context.origin()
		val pen = Pen(level, r)
		if (cfg.blocks.isEmpty() || !ChunkWriter.isTerrain(level.getBlockState(o.below())) || !level.getBlockState(o).fluidState.isEmpty) {
			return false
		}
		val size = (cfg.size.min + r.nextFloat() * (cfg.size.max - cfg.size.min)).toDouble()
		val x = o.x
		val y = o.y
		val z = o.z
		// Mostly the main block, with clustered patches of the others.
		val salt = r.nextInt(1000)
		fun rock(px: Int, py: Int, pz: Int): BlockState {
			val n = cfg.blocks.size
			if (n == 1) return cfg.blocks[0]
			val v = LNoise.noise2(level.seed xor 0x70C4L, (px + py * 0.6 + salt) / 3.5, (pz - py * 0.6) / 3.5)
			return if (v < 0.15) cfg.blocks[0] else cfg.blocks[1 + (abs(px * 31 + py * 17 + pz * 13) % (n - 1))]
		}
		fun put(px: Int, py: Int, pz: Int) {
			if (!level.getBlockState(pen.p.set(px, py, pz)).`is`(Blocks.BEDROCK)) pen.set(px, py, pz, rock(px, py, pz))
		}
		/** Fills a column from the ground up to [top], and down until it meets terrain. */
		fun column(px: Int, pz: Int, top: Int) {
			var yy = top
			while (yy > y - 8) {
				if (yy < y && ChunkWriter.isTerrain(pen.get(px, yy, pz))) break
				put(px, yy, pz)
				yy--
			}
		}
		when (cfg.kind) {
			"boulder" -> {
				val ra = size * (0.75 + r.nextFloat() * 0.6)
				val rb = size * (0.75 + r.nextFloat() * 0.6)
				val rc = size * (0.7 + r.nextFloat() * 0.5)
				val cy = y - 1 + rc * (0.25 + r.nextFloat() * 0.45)
				val ir = (max(ra, rb) + 1).toInt()
				val s2 = r.nextInt(1000)
				for (dx in -ir..ir) {
					for (dz in -ir..ir) {
						for (dy in -ir..ir) {
							val ey = (y + dy - cy) / rc
							val q = (dx / ra).pow(2) + (dz / rb).pow(2) + ey * ey
							val lump = 0.22 * LNoise.noise2(level.seed xor 0xB01DL, (x + dx + s2) / 2.2 + dy * 0.4, (z + dz) / 2.2 - dy * 0.4)
							if (q <= 1 + lump) put(x + dx, y + dy, z + dz)
						}
					}
				}
			}
			"outcrop" -> {
				val a = r.nextDouble() * Math.PI * 2
				val len = (size * 2.5 + 2).toInt()
				val dip = 0.35 + r.nextFloat() * 0.5
				val beds = 1 + r.nextInt(3)
				for (b in 0 until beds) {
					val ox = x + (cos(a) * b * (len * 0.55)).roundToInt()
					val oz = z + (sin(a) * b * (len * 0.55)).roundToInt()
					val w = 1.5 + r.nextFloat() * size
					for (i in 0..len) {
						for (j in -w.toInt() - 1..w.toInt() + 1) {
							val edge = abs(j) / w
							if (edge > 1) continue
							val px = ox + (cos(a) * i - sin(a) * j).roundToInt()
							val pz = oz + (sin(a) * i + cos(a) * j).roundToInt()
							val top = y - 1 + (i * dip * (1 - edge * edge * 0.6)).toInt()
							if (top >= y) column(px, pz, top)
						}
					}
				}
			}
			"spire" -> {
				val h = (size * (2.2 + r.nextFloat() * 1.6)).toInt() + 2
				val base = min(2.6, 0.8 + size * 0.45)
				val hoodoo = r.nextBoolean()
				val lean = r.nextDouble() * Math.PI * 2
				for (i in -1..h) {
					val t = max(0, i) / h.toDouble()
					var rad = base * (1 - 0.75 * t)
					if (hoodoo && t > 0.8) rad = base * 0.75 else if (hoodoo && t > 0.6) rad = base * 0.3
					val cx = x + cos(lean) * t * size * 0.5
					val cz = z + sin(lean) * t * size * 0.5
					val ir = rad.toInt() + 1
					for (dx in -ir..ir) {
						for (dz in -ir..ir) {
							val px = cx.roundToInt() + dx
							val pz = cz.roundToInt() + dz
							if (sqrt((px - cx).pow(2) + (pz - cz).pow(2)) <= max(0.5, rad)) {
								if (i <= 0) column(px, pz, y + i) else put(px, y + i, pz)
							}
						}
					}
				}
			}
			"mound" -> {
				val rad = size * (1.0 + r.nextFloat() * 0.5)
				val h = size * (0.5 + r.nextFloat() * 0.8)
				val ir = rad.toInt() + 1
				for (dx in -ir..ir) {
					for (dz in -ir..ir) {
						val d = sqrt((dx * dx + dz * dz).toDouble()) / rad
						if (d > 1) continue
						val top = y - 1 + (h * (1 - d * d) + 0.4 * LNoise.noise2(level.seed xor 0x3077L, (x + dx) / 2.0, (z + dz) / 2.0)).roundToInt()
						if (top >= y) column(x + dx, z + dz, top)
					}
				}
			}
			"arch" -> {
				val a = r.nextDouble() * Math.PI
				val half = size * 1.6 + 2
				val h = size * (1.4 + r.nextFloat() * 0.8) + 2
				val thick = 1.6 + r.nextFloat() * 1.2
				val ir = (half + thick).toInt() + 1
				for (i in -ir..ir) {
					for (j in -1..1) {
						val px = x + (cos(a) * i - sin(a) * j).roundToInt()
						val pz = z + (sin(a) * i + cos(a) * j).roundToInt()
						if (abs(j) == 1 && r.nextFloat() < 0.35) continue
						for (dy in -3..(h + thick).toInt() + 1) {
							val yy = max(0, dy).toDouble()
							val inner = (i / half).pow(2) + (yy / h).pow(2)
							val outer = (i / (half + thick)).pow(2) + (yy / (h + thick)).pow(2)
							if (outer <= 1 && inner >= 1) {
								if (dy < 0 && ChunkWriter.isTerrain(pen.get(px, y + dy, pz))) continue
								put(px, y + dy, pz)
							}
						}
					}
				}
			}
			"driftwood" -> {
				val east = r.nextBoolean()
				val len = (size * 2).toInt() + 2
				val log = cfg.blocks[0]
				for (i in 0 until len) {
					val px = x + (if (east) i else 0)
					val pz = z + (if (east) 0 else i)
					if (!pen.free(px, y, pz) || !pen.get(px, y - 1, pz).blocksMotion()) break
					pen.log(px, y, pz, log, if (east) Direction.Axis.X else Direction.Axis.Z)
					if (i == len / 2 && r.nextBoolean()) pen.log(px, y + 1, pz, log)
					if (i == len / 3 && r.nextBoolean()) pen.log(px + (if (east) 0 else 1), y, pz + (if (east) 1 else 0), log, if (east) Direction.Axis.Z else Direction.Axis.X)
				}
			}
			else -> return false
		}
		return true
	}
}
