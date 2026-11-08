package io.github.menonng.biomesmod.terrain.field.mode

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.menonng.biomesmod.terrain.field.ChunkFrame
import io.github.menonng.biomesmod.terrain.field.Column
import io.github.menonng.biomesmod.terrain.field.Common
import io.github.menonng.biomesmod.terrain.field.Coverage
import io.github.menonng.biomesmod.terrain.field.FieldPalette
import io.github.menonng.biomesmod.terrain.field.Shaper
import io.github.menonng.biomesmod.terrain.field.ShaperMode
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import java.util.Optional
import kotlin.math.floor
import kotlin.math.max

/**
 * `flatten`: a dead-flat pan (Salar de Uyuni, Lake Gairdner). Optional 1-block water film in noise patches (the Uyuni
 * mirror) and a polygonal crust pattern of raised salt ridges drawn with a second surface block.
 *
 * @param level absolute pan Y; default: the regional terrain height (heavily smoothed) + [offset], never below sea
 *   level + 1, so a pan on a plateau stays on the plateau instead of carving a pit
 * @param offset added to the regional height when [level] is absent
 * @param relief amplitude (blocks) of a very gentle undulation; keep under 1 for a true pan
 * @param waterCoverage fraction of the pan covered by a 1-block water film (0 = dry)
 * @param waterScale size of the film patches
 * @param crust fraction of the pan showing polygon ridges (0 = none)
 * @param crustCell polygon size in blocks
 * @param crustBlock block of the polygon ridges
 */
data class FlattenMode(
	override val common: Common, val level: Optional<Int>, val offset: Int, val relief: Double, val waterCoverage: Double,
	val waterScale: Double, val crust: Double, val crustCell: Double, val crustBlock: Optional<BlockState>,
) : ShaperMode {
	override val type: ShaperMode.Type get() = ShaperMode.Type.FLATTEN

	override fun createShaper(seed: Long): Shaper = S(seed)

	private inner class S(seed: Long) : Shaper(seed) {
		private val waterT = Coverage.threshold(waterCoverage, 3)
		private val crustT = Coverage.threshold(crust, 2)
		private val ridge: BlockState = crustBlock.orElseGet { P.id("biomesmod:white_sand") }

		override fun defaultPalette(): FieldPalette = P.palette(P.layers(Blocks.CALCITE, 2, "white_terracotta", 2), P.layers(Blocks.CALCITE, 2),
			P.strata(Blocks.SMOOTH_SANDSTONE, 3, "white_terracotta", 1), 6)

		override fun prepare(frame: ChunkFrame) {
			if (level.isEmpty) {
				frame.prepareBase(8)
			}
		}

		override fun column(frame: ChunkFrame, x: Int, z: Int, i: Int, out: Column) {
			val pan: Int = level.orElseGet { max(frame.seaLevel + 1, floor(frame.blurBase(x, z) + 0.5).toInt() + offset) }
			val xd = x.toDouble()
			val zd = z.toDouble()
			out.ground = pan + (if (relief > 0) relief * noise.fbm(xd, zd, 220.0, 2) else 0.0)
			if (waterCoverage > 0 && noise.fbm(xd + 4111, zd - 977, waterScale, 3) > waterT) {
				out.water = pan + 2 // one block of water over the pan
			}
			if (crust > 0 && noise.fbm(xd - 3301, zd + 1907, 90.0, 2) > crustT) {
				// Polygon ridges: Voronoi cell borders, slightly wobbly so they never look ruled.
				val wx = xd + 1.6 * noise.simplex(xd / 11.0, zd / 11.0)
				val wz = zd + 1.6 * noise.simplex(xd / 11.0 + 31.7, zd / 11.0)
				if (noise.voronoiEdge(wx / crustCell, wz / crustCell, 0.85) < 0.09) {
					out.surface = ridge
					out.surfaceDepth = 1
				}
			}
		}
	}

	companion object {
		@JvmField
		val CODEC: MapCodec<FlattenMode> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Common.MAP_CODEC.forGetter(FlattenMode::common),
				Codec.INT.optionalFieldOf("level").forGetter(FlattenMode::level),
				Codec.intRange(-64, 128).optionalFieldOf("offset", 0).forGetter(FlattenMode::offset),
				Codec.doubleRange(0.0, 8.0).optionalFieldOf("relief", 0.0).forGetter(FlattenMode::relief),
				Codec.doubleRange(0.0, 1.0).optionalFieldOf("water_coverage", 0.0).forGetter(FlattenMode::waterCoverage),
				Codec.doubleRange(8.0, 4096.0).optionalFieldOf("water_scale", 160.0).forGetter(FlattenMode::waterScale),
				Codec.doubleRange(0.0, 1.0).optionalFieldOf("crust", 0.0).forGetter(FlattenMode::crust),
				Codec.doubleRange(3.0, 64.0).optionalFieldOf("crust_cell", 7.0).forGetter(FlattenMode::crustCell),
				BlockState.CODEC.optionalFieldOf("crust_block").forGetter(FlattenMode::crustBlock),
			).apply(i) { common, level, offset, relief, waterCoverage, waterScale, crust, crustCell, crustBlock ->
				FlattenMode(common, level, offset, relief, waterCoverage, waterScale, crust, crustCell, crustBlock)
			}
		}
	}
}
