package io.github.menonng.biomesmod.terrain.landform

import io.github.menonng.biomesmod.compat.LandformCompat
import io.github.menonng.biomesmod.compat.LevelCompat
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.placement.PlacedFeature
import net.minecraft.world.level.material.Fluids
import kotlin.math.max
import kotlin.math.min

/**
 * Writes landform columns into ONE chunk. Tracks each column's current terrain top so overlapping instances only add
 * mass. All writes are clipped to the chunk (contract rule 1).
 */
internal class ChunkWriter(
	@JvmField val level: WorldGenLevel,
	@JvmField val generator: ChunkGenerator,
	@JvmField val x0: Int,
	@JvmField val z0: Int,
	@JvmField val sea: Int,
) {
	@JvmField
	val minY: Int = LevelCompat.minY(level)

	@JvmField
	val maxY: Int = LandformCompat.maxY(level)

	/** Current terrain top per column. */
	private val top = IntArray(256)

	/** Original water surface per column (Col.NONE if dry). */
	private val water = IntArray(256)
	private val pos = BlockPos.MutableBlockPos()

	@JvmField
	var writes = 0

	init {
		// Finished chunks (dev showcase) only keep the final heightmaps; worldgen chunks have the _WG ones.
		val floorType = if (level.getChunk(x0 shr 4, z0 shr 4) is LevelChunk) Heightmap.Types.OCEAN_FLOOR else Heightmap.Types.OCEAN_FLOOR_WG
		for (dz in 0 until 16) {
			for (dx in 0 until 16) {
				val x = x0 + dx
				val z = z0 + dz
				var y = level.getHeight(floorType, x, z) - 1
				while (y > minY && !isTerrain(get(x, y, z))) {
					y--
				}
				val i = dz * 16 + dx
				top[i] = y
				water[i] = Col.NONE
				if (isWater(get(x, y + 1, z))) {
					var s: Int
					if (y + 1 <= sea && isWater(get(x, sea, z)) && !isWater(get(x, sea + 1, z))) {
						s = sea
					} else {
						s = y + 1
						while (s + 1 < maxY && isWater(get(x, s + 1, z))) {
							s++
						}
					}
					water[i] = s
				}
			}
		}
	}

	fun inside(x: Int, z: Int): Boolean = x >= x0 && x < x0 + 16 && z >= z0 && z < z0 + 16

	fun top(x: Int, z: Int): Int = top[(z - z0) * 16 + (x - x0)]

	fun waterSurface(x: Int, z: Int): Int = water[(z - z0) * 16 + (x - x0)]

	fun get(x: Int, y: Int, z: Int): BlockState = level.getBlockState(pos.set(x, y, z))

	fun set(x: Int, y: Int, z: Int, s: BlockState) {
		if (y < minY || y >= maxY || !inside(x, z)) {
			return
		}
		level.setBlock(pos.set(x, y, z), s, FLAGS)
		writes++
	}

	private fun mat(lf: Landform, c: Col, x: Int, y: Int, z: Int, depth: Int, wet: Boolean): BlockState {
		val cap = lf.cap
		if (c.cap && cap != null && !wet && c.span == c.top) {
			if (depth == 0) {
				return cap.block
			}
			if (depth < cap.depth) {
				return cap.under
			}
		}
		return lf.block(x, y, z, depth, c, wet)
	}

	fun apply(lf: Landform, x: Int, z: Int, c: Col) {
		val i = (z - z0) * 16 + (x - x0)
		val g = top[i]
		val fluid = c.fluid ?: WATER
		if (c.top != Col.NONE) {
			c.span = c.top
			var t = min(max(c.top, minY + 1), maxY - 2)
			if (c.blend < 1.0 && t > g) {
				t = g + Math.round((t - g) * max(0.0, c.blend)).toInt()
			}
			val fluidTop = max(c.water, water[i])
			val wet = t < fluidTop
			if (t > g) {
				val gaps = c.gaps
				if (gaps == null || c.gapCount == 0) {
					for (y in g + 1..t) {
						set(x, y, z, mat(lf, c, x, y, z, t - y, wet))
					}
				} else {
					// Walk down from the top; every gap ends a span, the first solid block below it starts the next one.
					// Gaps are ascending and disjoint.
					var gi = c.gapCount - 1
					while (gi >= 0 && gaps[gi * 2] > t) {
						gi--
					}
					var afterGap = false
					for (y in t downTo g + 1) {
						while (gi >= 0 && y < gaps[gi * 2]) {
							gi--
						}
						if (gi >= 0 && y <= gaps[gi * 2 + 1]) {
							afterGap = true
							continue
						}
						if (afterGap) {
							afterGap = false
							c.span = y
						}
						set(x, y, z, mat(lf, c, x, y, z, (if (c.span == c.top) t else c.span) - y, wet))
					}
					c.span = c.top
				}
				top[i] = t
			} else if (t < g && c.carve) {
				for (y in t + 1..g) {
					set(x, y, z, if (y <= fluidTop) (if (y <= c.water) fluid else WATER) else AIR)
				}
				top[i] = t
				val p = max(1, c.paint)
				for (y in t - p + 1..t) {
					set(x, y, z, mat(lf, c, x, y, z, t - y, wet))
				}
			} else if (c.paint > 0) {
				for (y in g - c.paint + 1..g) {
					set(x, y, z, mat(lf, c, x, y, z, g - y, g < fluidTop))
				}
			}
		}
		if (c.water != Col.NONE) {
			for (y in top[i] + 1..c.water) {
				set(x, y, z, fluid)
			}
		}
		if (c.voidLo != Col.NONE && c.voidHi >= c.voidLo) {
			val hi = min(c.voidHi, top[i] - 1)
			for (y in c.voidLo..hi) {
				set(x, y, z, if (y <= c.voidWater) WATER else AIR)
			}
		}
	}

	/** Makes [fromY, toY] of a basin wall column solid wherever it is open (air or plants), so water cannot leak. */
	fun seal(lf: Landform, x: Int, z: Int, fromY: Int, toY: Int) {
		val i = (z - z0) * 16 + (x - x0)
		for (y in fromY..toY) {
			val s = get(x, y, z)
			if (s.isAir || (!isTerrain(s) && s.fluidState.isEmpty)) {
				set(x, y, z, lf.sealBlock(x, y, z))
			}
		}
		if (toY > top[i]) {
			top[i] = toY
		}
	}

	fun setTop(x: Int, z: Int, y: Int) {
		top[(z - z0) * 16 + (x - x0)] = y
	}

	fun scheduleWaterTick(x: Int, y: Int, z: Int) {
		if (inside(x, z)) {
			level.scheduleTick(pos.set(x, y, z).immutable(), Fluids.WATER, 1)
		}
	}

	fun placeFeature(feature: Holder<PlacedFeature>, x: Int, y: Int, z: Int, seed: Long) {
		val pos = BlockPos(x, y, z)
		try {
			feature.value().place(level, generator, RandomSource.create(seed), pos)
		} catch (e: IllegalStateException) {
			// A placed feature with a biome filter cannot be placed from inside another feature: place its configured
			// feature directly instead of failing the chunk.
			feature.value().feature().value().place(level, generator, RandomSource.create(seed), pos)
		}
	}

	companion object {
		@JvmField
		val AIR: BlockState = Blocks.AIR.defaultBlockState()

		@JvmField
		val WATER: BlockState = Blocks.WATER.defaultBlockState()
		private const val FLAGS = Block.UPDATE_CLIENTS or Block.UPDATE_KNOWN_SHAPE

		fun gridIndex(dx: Int, dz: Int): Int = (dz + 1) * 18 + (dx + 1)

		fun isTerrain(s: BlockState): Boolean = s.blocksMotion() && !s.`is`(BlockTags.LEAVES) && !s.`is`(BlockTags.LOGS)

		fun isWater(s: BlockState): Boolean = s.fluidState.`is`(FluidTags.WATER)
	}
}
