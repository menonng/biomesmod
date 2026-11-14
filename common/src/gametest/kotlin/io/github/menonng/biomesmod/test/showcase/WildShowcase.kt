package io.github.menonng.biomesmod.test.showcase

import io.github.menonng.biomesmod.BiomesMod
import io.github.menonng.biomesmod.debug.DebugRender
import io.github.menonng.biomesmod.terrain.landform.LandformFeatures
import io.github.menonng.biomesmod.terrain.landform.Span
import io.github.menonng.biomesmod.terrain.landform.WildRockFeature
import io.github.menonng.biomesmod.terrain.landform.WildTreeFeature
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import java.util.Optional

/**
 * Dev showcase for biomesmod:wild_tree and biomesmod:wild_rock (`-Pshowcase=wild -Prender=true`): on a levelled pad,
 * one row per tree style and one row per rock kind, seven specimens each, rendered as `wild_trees` and `wild_rocks`.
 */
object WildShowcase {
	private const val STEP = 14
	private const val N = 7

	private fun s(b: net.minecraft.world.level.block.Block): BlockState = b.defaultBlockState()

	fun run(server: MinecraftServer) {
		val level = server.getLevel(Level.OVERWORLD)!!
		val center = FieldShowcase.find(level, { it.`is`(Biomes.PLAINS) }, BlockPos(-900, 64, 700)) ?: return
		val random = RandomSource.create(11)
		val gen = level.chunkSource.generator

		val trees = arrayOf(
			WildTreeFeature.Config("conifer", s(Blocks.SPRUCE_LOG), s(Blocks.SPRUCE_LEAVES), Span.of(6.0, 13.0)),
			WildTreeFeature.Config("columnar", s(Blocks.SPRUCE_LOG), s(Blocks.SPRUCE_LEAVES), Span.of(6.0, 11.0)),
			WildTreeFeature.Config("krummholz", s(Blocks.BIRCH_LOG), s(Blocks.BIRCH_LEAVES), Span.of(2.0, 5.0)),
			WildTreeFeature.Config("broadleaf", s(Blocks.OAK_LOG), s(Blocks.OAK_LEAVES), Span.of(5.0, 9.0)),
			WildTreeFeature.Config("palm", s(Blocks.JUNGLE_LOG), s(Blocks.JUNGLE_LEAVES), Span.of(6.0, 12.0)),
			WildTreeFeature.Config("flat_top", s(Blocks.ACACIA_LOG), s(Blocks.ACACIA_LEAVES), Span.of(3.0, 6.0)),
			WildTreeFeature.Config("shrub", s(Blocks.OAK_LOG), s(Blocks.AZALEA_LEAVES), Span.of(1.0, 2.0)),
		)
		val y = pad(level, center, trees.size)
		for ((row, cfg) in trees.withIndex()) {
			for (k in 0 until N) {
				val p = BlockPos(center.x + (k - N / 2) * STEP, y, center.z + (row * 2 - trees.size + 1) * STEP / 2)
				LandformFeatures.WILD_TREE.place(FeaturePlaceContext(Optional.empty(), level, gen, random, p, cfg))
			}
		}
		DebugRender.render(level, BlockPos(center.x, y, center.z), 112, "wild_trees")
		DebugRender.iso(level, BlockPos(center.x, y, center.z), 112, "wild_trees", 5)

		val stone = listOf(s(Blocks.STONE), s(Blocks.ANDESITE), s(Blocks.MOSSY_COBBLESTONE))
		val red = listOf(s(Blocks.RED_SANDSTONE), s(Blocks.SMOOTH_RED_SANDSTONE), s(Blocks.TERRACOTTA))
		val rocks = arrayOf(
			WildRockFeature.Config("boulder", stone, Span.of(1.2, 3.4)),
			WildRockFeature.Config("outcrop", stone, Span.of(1.5, 3.5)),
			WildRockFeature.Config("spire", red, Span.of(1.5, 3.5)),
			WildRockFeature.Config("mound", listOf(s(Blocks.CALCITE), s(Blocks.MOSS_BLOCK)), Span.of(2.0, 4.0)),
			WildRockFeature.Config("arch", red, Span.of(2.0, 4.5)),
			WildRockFeature.Config("driftwood", listOf(s(Blocks.STRIPPED_SPRUCE_LOG)), Span.of(1.0, 3.0)),
			WildRockFeature.Config("spire", listOf(s(Blocks.PACKED_ICE), s(Blocks.BLUE_ICE), s(Blocks.SNOW_BLOCK)), Span.of(1.5, 3.5)),
		)
		val c2 = BlockPos(center.x + 240, center.y, center.z)
		val y2 = pad(level, c2, rocks.size)
		for ((row, cfg) in rocks.withIndex()) {
			for (k in 0 until N) {
				val p = BlockPos(c2.x + (k - N / 2) * STEP, y2, c2.z + (row * 2 - rocks.size + 1) * STEP / 2)
				LandformFeatures.WILD_ROCK.place(FeaturePlaceContext(Optional.empty(), level, gen, random, p, cfg))
			}
		}
		DebugRender.render(level, BlockPos(c2.x, y2, c2.z), 112, "wild_rocks")
		DebugRender.iso(level, BlockPos(c2.x, y2, c2.z), 112, "wild_rocks", 5)
		BiomesMod.LOGGER.info("[showcase] wild at {}", center.toShortString())
	}

	/** Levels a grass pad round [c] and returns the first free Y above it. */
	private fun pad(level: ServerLevel, c: BlockPos, rows: Int): Int {
		val hx = N * STEP / 2 + 8
		val hz = rows * STEP / 2 + 8
		for (cz in (c.z - hz shr 4) - 1..(c.z + hz shr 4) + 1) {
			for (cx in (c.x - hx shr 4) - 1..(c.x + hx shr 4) + 1) {
				level.getChunk(cx, cz)
			}
		}
		val y = 90
		val p = BlockPos.MutableBlockPos()
		for (x in c.x - hx..c.x + hx) {
			for (z in c.z - hz..c.z + hz) {
				for (yy in y - 4..y + 40) {
					level.setBlock(p.set(x, yy, z), if (yy < y - 1) s(Blocks.DIRT) else if (yy == y - 1) s(Blocks.GRASS_BLOCK) else s(Blocks.AIR), 2)
				}
			}
		}
		return y
	}
}
