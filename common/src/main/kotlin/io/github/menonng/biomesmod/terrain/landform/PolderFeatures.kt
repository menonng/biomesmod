package io.github.menonng.biomesmod.terrain.landform

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.biome.Biome
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf
import net.minecraft.world.level.block.state.properties.Half
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `biomesmod:flower_strips`: Dutch bulb fields. The land is divided into rectangular fields (each with its own row
 * direction, almost square to its neighbours'); every field is planted in long single-colour strips a few blocks wide,
 * colours repeating in a field-specific order, with a bare grass headland around each field and some fields left
 * fallow. Place once per chunk (no in_square); it fills the chunk's columns of the origin's biome.
 */
class FlowerStripsFeature : Feature<FlowerStripsFeature.Config>(Config.CODEC) {
	data class Config(val flowers: List<BlockState>, val stripWidth: Int, val fieldSize: Int, val fallow: Float,
		val biome: java.util.Optional<ResourceKey<Biome>>) : FeatureConfiguration {
		companion object {
			@JvmField
			val CODEC: Codec<Config> = RecordCodecBuilder.create { i ->
				i.group(
					BlockState.CODEC.listOf().fieldOf("flowers").forGetter(Config::flowers),
					Codec.intRange(1, 8).optionalFieldOf("strip_width", 3).forGetter(Config::stripWidth),
					Codec.intRange(12, 256).optionalFieldOf("field_size", 56).forGetter(Config::fieldSize),
					Codec.floatRange(0f, 1f).optionalFieldOf("fallow", 0.2f).forGetter(Config::fallow),
					ResourceKey.codec(Registries.BIOME).optionalFieldOf("biome").forGetter(Config::biome),
				).apply(i) { flowers, stripWidth, fieldSize, fallow, biome -> Config(flowers, stripWidth, fieldSize, fallow, biome) }
			}
		}
	}

	override fun place(context: FeaturePlaceContext<Config>): Boolean {
		val level = context.level()
		val cfg = context.config()
		val o = context.origin()
		val x0 = o.x and 15.inv()
		val z0 = o.z and 15.inv()
		val key = cfg.biome.orElse(null)
		val biome = level.getBiome(o)
		val seed = level.seed xor 0x7071L
		val p = BlockPos.MutableBlockPos()
		var placed = false
		val fs = cfg.fieldSize.toDouble()
		for (dz in 0 until 16) {
			for (dx in 0 until 16) {
				val x = x0 + dx
				val z = z0 + dz
				// Fields: a jittered grid; the headland is the last two blocks of each field.
				val gx = x + 9 * LNoise.noise2(seed, x / 90.0, z / 90.0)
				val gz = z + 9 * LNoise.noise2(seed xor 1, x / 90.0, z / 90.0)
				val fx = floor(gx / fs).toInt()
				val fz = floor(gz / (fs * 0.7)).toInt()
				val ux = gx - fx * fs
				val uz = gz - fz * fs * 0.7
				if (ux < 2 || uz < 2) {
					continue
				}
				val fh = LNoise.hash(seed, fx, fz)
				if (LNoise.unit(fh) < cfg.fallow) {
					continue
				}
				// The terrain shaper may have lowered the ground below the heightmap: walk down to the real surface.
				var y = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z)
				var guard = 0
				while (guard++ < 48 && level.getBlockState(p.set(x, y - 1, z)).isAir) {
					y--
				}
				p.set(x, y, z)
				if (!level.getBlockState(p).isAir || !level.getBlockState(p.below()).`is`(Blocks.GRASS_BLOCK)) {
					continue
				}
				val here = level.getBiome(p)
				if (if (key != null) !here.`is`(key) else here != biome) {
					continue
				}
				// Rows run along x or z (decided per field); one blank row between strips of different colour.
				val along = if ((fh ushr 9) and 1L == 0L) ux else uz
				val sw = cfg.stripWidth + 1
				val strip = floor(along / sw).toInt()
				if (along - strip * sw >= cfg.stripWidth) {
					continue
				}
				val n = cfg.flowers.size
				val order = ((fh ushr 13) and 0xFFFF).toInt()
				val idx = Math.floorMod(strip * (1 + (order % (n - 1).coerceAtLeast(1))) + order, n)
				level.setBlock(p, cfg.flowers[idx], 2)
				placed = true
			}
		}
		return placed
	}
}

/**
 * `biomesmod:windmill`: a Dutch smock mill. Brick plinth with a door, an eight-sided tapering timber tower with corner
 * posts and small windows, a reefing stage (gallery) all round, a boat-shaped cap turned into the wind and four lattice
 * sails with half-set cloth. Built on a stone foundation that fills down to uneven ground; skipped where the ground is
 * not land.
 */
class WindmillFeature : Feature<WindmillFeature.Config>(Config.CODEC) {
	data class Config(val height: Span) : FeatureConfiguration {
		companion object {
			@JvmField
			val CODEC: Codec<Config> = RecordCodecBuilder.create { i ->
				i.group(Span.CODEC.optionalFieldOf("height", Span.of(13.0, 17.0)).forGetter(Config::height)).apply(i) { Config(it) }
			}
		}
	}

	override fun place(context: FeaturePlaceContext<Config>): Boolean {
		val level = context.level()
		val r = context.random()
		val o = context.origin()
		val ground = level.getBlockState(o.below())
		if (!io.github.menonng.biomesmod.compat.LandformCompat.isSoil(ground) && !ground.`is`(BlockTags.SAND)) {
			return false
		}
		val p = BlockPos.MutableBlockPos()
		val y0 = o.y
		val h = (context.config().height.min + r.nextFloat() * (context.config().height.max - context.config().height.min)).roundToInt()
		val facing = Direction.Plane.HORIZONTAL.getRandomDirection(r)
		val bricks = Blocks.BRICKS.defaultBlockState()
		val stone = Blocks.STONE_BRICKS.defaultBlockState()
		val planks = Blocks.DARK_OAK_PLANKS.defaultBlockState()
		val post = Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState()
		val thatch = Blocks.SPRUCE_PLANKS.defaultBlockState()
		val pane = Blocks.GLASS_PANE.defaultBlockState()
		val stage = Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP)
		val rail = Blocks.SPRUCE_FENCE.defaultBlockState()
		// Foundation: fill down to the ground under the whole plinth.
		val baseR = 4.2
		for (dz in -5..5) {
			for (dx in -5..5) {
				if (oct(dx, dz) > baseR) {
					continue
				}
				var y = y0 - 1
				while (y > y0 - 8 && !ChunkWriter.isTerrain(level.getBlockState(p.set(o.x + dx, y, o.z + dz)))) {
					level.setBlock(p, stone, 2)
					y--
				}
			}
		}
		// Plinth (3 high) and the tapering tower.
		val plinth = 3
		for (y in 0 until h) {
			val t = y / h.toDouble()
			val rad = if (y < plinth) baseR else 3.6 - 1.4 * ((y - plinth) / (h - plinth).toDouble())
			val ri = rad.toInt() + 1
			for (dz in -ri..ri) {
				for (dx in -ri..ri) {
					val d = oct(dx, dz)
					if (d > rad || d <= rad - 1.0) {
						if (d <= rad - 1.0) {
							set(level, p.set(o.x + dx, y0 + y, o.z + dz), Blocks.AIR.defaultBlockState(), true)
						}
						continue
					}
					val corner = abs(abs(dx) - abs(dz)) <= 0 || (abs(dx) == ri - 1 && abs(dz) == ri - 1)
					val wall = when {
						y < plinth -> if (y == plinth - 1) stone else bricks
						corner -> post
						else -> if (t > 0.75) thatch else planks
					}
					set(level, p.set(o.x + dx, y0 + y, o.z + dz), wall, false)
				}
			}
		}
		// Door on the facing side, windows on the tower.
		val door = Blocks.DARK_OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, facing)
		val dx0 = facing.stepX * 4
		val dz0 = facing.stepZ * 4
		level.setBlock(p.set(o.x + dx0, y0, o.z + dz0), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 2)
		level.setBlock(p.set(o.x + dx0, y0 + 1, o.z + dz0), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2)
		for ((k, dir) in Direction.Plane.HORIZONTAL.withIndex()) {
			val wy = y0 + plinth + 3 + (k % 2) * 4
			val rr = (3.6 - 1.4 * ((wy - y0 - plinth) / (h - plinth).toDouble())).toInt()
			if (wy < y0 + h - 2) {
				set(level, p.set(o.x + dir.stepX * rr, wy, o.z + dir.stepZ * rr), pane, true)
			}
		}
		// Reefing stage at the top of the plinth, with a rail.
		val sy = y0 + plinth + 2
		for (dz in -5..5) {
			for (dx in -5..5) {
				val d = oct(dx, dz)
				if (d <= 4.9 && d > 3.0) {
					set(level, p.set(o.x + dx, sy, o.z + dz), stage, false)
					if (d > 4.0) {
						set(level, p.set(o.x + dx, sy + 1, o.z + dz), rail, false)
					}
				}
			}
		}
		// Cap: a short gable along the facing axis.
		val top = y0 + h
		val side = facing.clockWise
		for (along in -3..3) {
			for (across in -2..2) {
				val roofY = top + 2 - abs(across)
				val bx = o.x + facing.stepX * along + side.stepX * across
				val bz = o.z + facing.stepZ * along + side.stepZ * across
				for (y in top..roofY) {
					val st = if (y == roofY && abs(across) > 0) {
						Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, if (across > 0) side.opposite else side)
							.setValue(StairBlock.HALF, Half.BOTTOM)
					} else {
						planks
					}
					set(level, p.set(bx, y, bz), st, false)
				}
			}
		}
		// Axle and four sails in the plane across the facing, centred in front of the cap.
		val ax = o.x + facing.stepX * 4
		val az = o.z + facing.stepZ * 4
		val ay = top + 1
		val axle = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, facing.axis)
		set(level, p.set(o.x + facing.stepX * 3, ay, o.z + facing.stepZ * 3), axle, true)
		set(level, p.set(ax, ay, az), axle, true)
		val sx = ax + facing.stepX
		val sz = az + facing.stepZ
		val stock = Blocks.STRIPPED_BIRCH_LOG.defaultBlockState()
		val cloth = B.of("white_wool")
		val lattice = Blocks.BIRCH_FENCE.defaultBlockState()
		val len = h - 2
		val turn = r.nextFloat() * 0.6
		for (arm in 0 until 4) {
			val a = turn + arm * Math.PI / 2
			val ca = cos(a)
			val sa = sin(a)
			for (s in 1..len) {
				val u = ca * s
				val v = sa * s
				val bx = sx + (side.stepX * u).roundToInt()
				val bz = sz + (side.stepZ * u).roundToInt()
				val by = ay + v.roundToInt()
				set(level, p.set(bx, by, bz), stock, true)
				if (s < 3) {
					continue
				}
				// Lattice beside the stock (trailing side), cloth on the outer half.
				for (w in 1..2) {
					val lu = u - sa * w
					val lv = v + ca * w
					val lx = sx + (side.stepX * lu).roundToInt()
					val lz = sz + (side.stepZ * lu).roundToInt()
					val ly = ay + lv.roundToInt()
					set(level, p.set(lx, ly, lz), if (s > len / 2 && (s + w) % 3 != 0) cloth else lattice, true)
				}
			}
		}
		return true
	}

	/** Octagonal distance (an octagon of "radius" r is everything with oct <= r). */
	private fun oct(dx: Int, dz: Int): Double {
		val ax = abs(dx).toDouble()
		val az = abs(dz).toDouble()
		return max(max(ax, az), (ax + az) / sqrt(2.0) * 1.0824)
	}

	private fun set(level: WorldGenLevel, pos: BlockPos, state: BlockState, force: Boolean) {
		val cur = level.getBlockState(pos)
		if (force || cur.isAir || cur.canBeReplaced() || cur.`is`(BlockTags.LEAVES) || cur.`is`(BlockTags.FLOWERS)) {
			level.setBlock(pos, state, 2)
		}
	}
}
