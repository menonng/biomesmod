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
import net.minecraft.world.level.block.VineBlock
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
 * * `coppice`: the trees of a lava-field forest (the gotjawal of Jeju). Two to five thin, crooked stems lean apart from
 *   one stool (regrowth after centuries of cutting) under one low, dense dome of evergreen leaves. Half of them have
 *   germinated on a mossy boulder and grip it: their roots run over the top of the rock and down its sides into the
 *   ground. Ivy climbs the stems, vines hang from the crown, moss and fern cover the rock. Roots on bare rock too.
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
			"coppice" -> soil || ChunkWriter.isTerrain(below)
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
					pen.blob(x + fx + wx * len * t, y + h - 0.2 - t * (0.5 + r.nextFloat()), z + fz + wz * len * t, 2.0 - 0.5 * t, 1.5 - 0.3 * t, leaves)
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
					val len = if (k % 2 == 0) 4 + r.nextInt(2) else 3 + r.nextInt(2)
					for (s in 1..len) {
						val drop = if (s <= 2) 0 else ((s - 2) * (s - 2) * 0.45).roundToInt()
						if (s == 1) pen.leaf(tx + (cos(fa) * s).roundToInt(), top + 1, tz + (sin(fa) * s).roundToInt(), leaves)
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
			"coppice" -> {
				// The boulder the tree has grown on, if it has: a mossy lump of lava two or three blocks high.
				var base = y
				val rockTops = ArrayList<IntArray>()
				if (r.nextFloat() < 0.5f) {
					val rr = 1.4 + r.nextFloat() * 1.1
					val rh = 2 + r.nextInt(2)
					val ir = rr.toInt() + 1
					for (dx in -ir..ir) {
						for (dz in -ir..ir) {
							val d = sqrt((dx * dx + dz * dz).toDouble()) / rr + r.nextFloat() * 0.2
							if (d > 1 || !pen.get(x + dx, y - 1, z + dz).blocksMotion()) continue
							val top = y - 1 + max(1, (rh * sqrt(1 - d * d) + 0.4).roundToInt())
							for (yy in y..top) {
								if (!pen.free(x + dx, yy, z + dz)) continue
								pen.set(x + dx, yy, z + dz, if (yy == top) (if (r.nextFloat() < 0.75f) MOSS else MOSSY) else if (r.nextFloat() < 0.7f) MOSSY else BASALT)
							}
							if (dx == 0 && dz == 0) base = top + 1
							rockTops.add(intArrayOf(x + dx, top + 1, z + dz))
						}
					}
				}
				// Stems: crooked, the side ones leaning out towards the light, some with a kink half way up.
				val stems = 2 + r.nextInt(4)
				val a0 = r.nextDouble() * Math.PI * 2
				val trunk = ArrayList<IntArray>()
				var sumX = 0.0
				var sumZ = 0.0
				var tallest = 0
				for (k in 0 until stems) {
					var a = a0 + k * Math.PI * 2 / stems + (r.nextDouble() - 0.5) * 0.9
					var lean = if (k == 0) 0.05 + r.nextFloat() * 0.12 else 0.14 + r.nextFloat() * 0.26
					val sh = if (k == 0) h else max(3, (h * (0.55 + r.nextFloat() * 0.4)).toInt())
					var fx = if (k == 0) 0.0 else cos(a) * 0.6
					var fz = if (k == 0) 0.0 else sin(a) * 0.6
					var px = x + fx.roundToInt()
					var pz = z + fz.roundToInt()
					for (i in 0 until sh) {
						val nx = x + fx.roundToInt()
						val nz = z + fz.roundToInt()
						if (nx != px && nz != pz) pen.log(nx, base + i, pz, log) // a diagonal step stays joined
						pen.log(nx, base + i, nz, log)
						trunk.add(intArrayOf(nx, base + i, nz))
						px = nx
						pz = nz
						if (i >= 1) {
							a += (r.nextDouble() - 0.5) * 0.7
							if (i == sh / 2 && r.nextFloat() < 0.4f) lean *= 1.8
							fx += cos(a) * lean
							fz += sin(a) * lean
						}
					}
					pen.blob(x + fx, base + sh + 0.2, z + fz, 2.0 + r.nextFloat() * 0.9, 1.3 + r.nextFloat() * 0.5, leaves)
					sumX += fx
					sumZ += fz
					tallest = max(tallest, sh)
				}
				// One low dome over all the stems, wider than it is deep: the closed evergreen canopy.
				pen.blob(x + sumX / stems, base + tallest - 0.2, z + sumZ / stems, 3.0 + r.nextFloat() * 1.4, 1.5 + r.nextFloat() * 0.6, leaves,
					0.85 + r.nextFloat() * 0.3, 0.85 + r.nextFloat() * 0.3)
				// Roots: over the boulder and down its sides into the ground, or a few steps over the rock round the stool.
				val roots = 3 + r.nextInt(3)
				for (k in 0 until roots) {
					val a = a0 + (k + 0.5) * Math.PI * 2 / roots + (r.nextDouble() - 0.5) * 0.6
					val axis = if (abs(cos(a)) > abs(sin(a))) Direction.Axis.X else Direction.Axis.Z
					var rx = x.toDouble()
					var rz = z.toDouble()
					var ry = base
					var grounded = 0
					val reach = 1 + r.nextInt(2)
					for (step in 0 until 7) {
						rx += cos(a)
						rz += sin(a)
						val qx = rx.roundToInt()
						val qz = rz.roundToInt()
						if (qx == x && qz == z) continue
						if (!pen.free(qx, ry, qz)) {
							if (!pen.free(qx, ry + 1, qz)) break
							ry++
						}
						if (!pen.get(qx, ry, qz).fluidState.isEmpty) break
						pen.log(qx, ry, qz, log, axis)
						var drop = 0
						while (drop < 5 && pen.free(qx, ry - 1, qz) && pen.get(qx, ry - 1, qz).fluidState.isEmpty) {
							ry--
							drop++
							pen.log(qx, ry, qz, log)
						}
						if (ry <= y) grounded++
						if (grounded >= reach || (base == y && step >= 1 + reach)) break
					}
				}
				// Ivy up the lower stems.
				for (t in trunk) {
					if (t[1] > base + h * 0.8 || r.nextFloat() > 0.35f) continue
					val d = Direction.Plane.HORIZONTAL.getRandomDirection(r)
					val vine = VINE.setValue(VineBlock.getPropertyForFace(d.opposite), true)
					for (yy in t[1] until t[1] + 2 + r.nextInt(3)) {
						if (!pen.get(t[0] + d.stepX, yy, t[2] + d.stepZ).isAir || !pen.get(t[0], yy, t[2]).`is`(BlockTags.LOGS)) break
						pen.set(t[0] + d.stepX, yy, t[2] + d.stepZ, vine)
					}
				}
				// Moss and fern on the boulder.
				for (t in rockTops) {
					if (!pen.get(t[0], t[1], t[2]).isAir) continue
					val f = r.nextFloat()
					if (f < 0.25f) {
						pen.set(t[0], t[1], t[2], MOSS_CARPET)
					} else if (f < 0.4f && pen.get(t[0], t[1] - 1, t[2]).`is`(Blocks.MOSS_BLOCK)) {
						pen.set(t[0], t[1], t[2], FERN)
					}
				}
				// Vines from the crown, on the side of a leaf with air next to it.
				for (k in 0 until 6) {
					val qx = x + r.nextInt(9) - 4
					val qz = z + r.nextInt(9) - 4
					val d = Direction.Plane.HORIZONTAL.getRandomDirection(r)
					for (yy in base + h + 2 downTo base + 2) {
						if (!pen.get(qx, yy, qz).`is`(BlockTags.LEAVES)) continue
						val vx = qx + d.stepX
						val vz = qz + d.stepZ
						if (!pen.get(vx, yy, vz).isAir) break
						val vine = VINE.setValue(VineBlock.getPropertyForFace(d.opposite), true)
						var vy = yy
						val len = 1 + r.nextInt(4)
						while (vy > yy - len && vy > base && pen.get(vx, vy, vz).isAir) {
							pen.set(vx, vy, vz, vine)
							vy--
						}
						break
					}
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
		private val MOSSY = Blocks.MOSSY_COBBLESTONE.defaultBlockState()
		private val MOSS = Blocks.MOSS_BLOCK.defaultBlockState()
		private val BASALT = Blocks.BASALT.defaultBlockState()
		private val MOSS_CARPET = Blocks.MOSS_CARPET.defaultBlockState()
		private val FERN = Blocks.FERN.defaultBlockState()
		private val VINE = Blocks.VINE.defaultBlockState()
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
 * * `sink`: a collapse pit, a bowl a few blocks deep lined with the rock, with fern and moss on its floor; one in three
 *   holds a pool (the hollows and wetlands of a lava field).
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
		if (cfg.kind == "spire" || cfg.kind == "arch" || cfg.kind == "mound" || cfg.kind == "sink") {
			for (d in arrayOf(intArrayOf(4, 0), intArrayOf(-4, 0), intArrayOf(0, 4), intArrayOf(0, -4))) {
				if (abs(level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE_WG, x + d[0], z + d[1]) - y) > 2) {
					return false
				}
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
				val hoodoo = r.nextInt(3) == 0
				val lean = r.nextDouble() * Math.PI * 2
				for (i in -1..h) {
					val t = max(0, i) / h.toDouble()
					var rad = base * (1 - 0.75 * t)
					if (hoodoo && t > 0.8) rad = base * 0.8 else if (hoodoo && t > 0.6) rad = base * 0.5
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
				val half = size * 0.9 + 2
				val h = size * (0.8 + r.nextFloat() * 0.5) + 2
				val thick = 2.0 + r.nextFloat() * 1.3
				val ir = (half + thick).toInt() + 1
				for (i in -ir..ir) {
					for (j in -1..1) {
						val px = x + (cos(a) * i - sin(a) * j).roundToInt()
						val pz = z + (sin(a) * i + cos(a) * j).roundToInt()
						if (abs(j) == 1 && abs(i) < half * 0.5 && r.nextFloat() < 0.3) continue
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
			"sink" -> {
				val rad = size * (1.6 + r.nextFloat() * 0.8)
				val depth = size * (0.8 + r.nextFloat() * 0.7)
				val wet = r.nextInt(3) == 0
				val waterY = y - 1 - max(1, (depth * 0.5).roundToInt())
				val ir = rad.toInt() + 2
				val s2 = r.nextInt(1000)
				for (dx in -ir..ir) {
					for (dz in -ir..ir) {
						val d = sqrt((dx * dx + dz * dz).toDouble()) / rad + 0.18 * LNoise.noise2(level.seed xor 0x51C4L, (x + dx + s2) / 2.6, (z + dz) / 2.6)
						if (d > 1) continue
						val px = x + dx
						val pz = z + dz
						val gy = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG, px, pz)
						val cut = (depth * (1 - d * d)).roundToInt()
						if (cut <= 0 || abs(gy - y) > 3) continue
						val floor = y - 1 - cut
						for (yy in gy - 1 downTo floor + 1) pen.set(px, yy, pz, AIR)
						put(px, floor, pz)
						if (wet && floor < waterY) {
							for (yy in floor + 1..waterY) pen.set(px, yy, pz, WATER)
						} else {
							val f = r.nextFloat()
							if (f < 0.3) pen.set(px, floor + 1, pz, FERN) else if (f < 0.5) pen.set(px, floor + 1, pz, MOSS_CARPET)
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

	companion object {
		private val AIR = Blocks.AIR.defaultBlockState()
		private val WATER = Blocks.WATER.defaultBlockState()
		private val FERN = Blocks.FERN.defaultBlockState()
		private val MOSS_CARPET = Blocks.MOSS_CARPET.defaultBlockState()
	}
}
