package io.github.menonng.biomesmod.terrain.field.mode

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.menonng.biomesmod.terrain.field.ChunkFrame
import io.github.menonng.biomesmod.terrain.field.Column
import io.github.menonng.biomesmod.terrain.field.Common
import io.github.menonng.biomesmod.terrain.field.Coverage
import io.github.menonng.biomesmod.terrain.field.FieldPalette
import io.github.menonng.biomesmod.terrain.field.Noise
import io.github.menonng.biomesmod.terrain.field.Shaper
import io.github.menonng.biomesmod.terrain.field.ShaperMode
import net.minecraft.world.level.block.Blocks
import kotlin.math.floor
import kotlin.math.min

/**
 * `terraces`: a hillside turned into a staircase of pools. The slope is the regional terrain plus a broad tilt; it is
 * cut into levels `step` blocks apart along noise-warped contours (so rims meander like real travertine, never ruled
 * lines). Each level is a pool `pool_depth` deep held by a rim one block higher, water filled to just under the rim.
 * - Pamukkale: step 2-3, pool_depth 1, calcite palette, many small lobes (`lobe` high).
 * - Plitvice: step 6-10, pool_depth 4-7, mossy rims, `falls` > 0 drops water over the barriers.
 */
data class TerracesMode(
	override val common: Common, val step: Int, val poolDepth: Int, val tilt: Double, val tiltScale: Double, val lobe: Double,
	val rimWidth: Double, val falls: Double, val lift: Int, val poolCoverage: Double, val poolScale: Double, val lobeScale: Double,
) : ShaperMode {
	override val type: ShaperMode.Type get() = ShaperMode.Type.TERRACES

	override fun createShaper(seed: Long): Shaper = S(seed)

	private inner class S(seed: Long) : Shaper(seed) {
		/**
		 * Rim meander warp (blocks). The frame's base grid only covers columns with margin + warp <= 16 around the chunk
		 * (bicubic needs one node either side), so the warp is capped and the noise clamped to [-1, 1].
		 */
		private val warp = min(9.0, lobe * 7)
		private val poolT = Coverage.threshold(poolCoverage, 3)

		override fun margin(): Int = 3

		override fun defaultPalette(): FieldPalette = P.palette(P.layers(Blocks.CALCITE, 3), P.layers(Blocks.CALCITE, 2),
			P.strata(Blocks.CALCITE, 4, Blocks.DIORITE, 1, Blocks.CALCITE, 3, Blocks.SMOOTH_QUARTZ, 1), 3)

		override fun prepare(frame: ChunkFrame) {
			frame.prepareBase(3)
		}

		/** Continuous slope height (before cutting into steps). Pure. */
		private fun slope(frame: ChunkFrame, x: Double, z: Double): Double {
			val warpX = x + warp * Noise.clamp(noise.fbm(x, z, lobeScale, 2), -1.0, 1.0)
			val warpZ = z + warp * Noise.clamp(noise.fbm(x + 517, z - 233, lobeScale, 2), -1.0, 1.0)
			return frame.blurBase(floor(warpX).toInt(), floor(warpZ).toInt()) + lift + tilt * noise.fbm01(warpX, warpZ, tiltScale, 2)
		}

		override fun column(frame: ChunkFrame, x: Int, z: Int, i: Int, out: Column) {
			val xd = x.toDouble()
			val zd = z.toDouble()
			if (poolCoverage < 1) {
				// Terraced pools only where the mask says so (a chain of lakes); elsewhere the slope stays natural.
				val m = Noise.smoothstep(poolT - 0.05, poolT + 0.12, noise.fbm(xd - 1931, zd + 4421, poolScale, 3))
				if (m <= 0) {
					out.blend = 0.0
					return
				}
				out.blend = m
			}
			val s = slope(frame, xd, zd)
			val k = floor(s / step)
			val level = k * step // pool rim top of this terrace
			val into = s - level // 0 at the outer (lower) edge, step at the inner edge
			// The rim is the band just inside the outer edge of each step.
			val rim = into < rimWidth
			val rimTop = level.toInt()
			if (rim || poolDepth == 0) {
				out.ground = rimTop.toDouble()
			} else {
				out.ground = (rimTop - poolDepth).toDouble()
				out.water = rimTop + 1 // water surface level with the rim top
			}
			if (falls > 0 && rim) {
				// Water spilling over the barrier: on the lower side of the rim, sometimes.
				val spot = noise.fbm(xd * 0.7, zd * 0.7, 9.0, 2)
				if (spot > 1.0 - falls * 1.6 && into < 0.6) {
					out.ground = (rimTop - step).toDouble()
					out.flags = out.flags or Column.FALL or Column.NO_WALL
					out.fallTop = rimTop + (if (poolDepth > 0) 0 else 1)
				}
			}
		}
	}

	companion object {
		@JvmField
		val CODEC: MapCodec<TerracesMode> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Common.MAP_CODEC.forGetter(TerracesMode::common),
				Codec.intRange(1, 24).optionalFieldOf("step", 3).forGetter(TerracesMode::step),
				Codec.intRange(0, 12).optionalFieldOf("pool_depth", 1).forGetter(TerracesMode::poolDepth),
				Codec.doubleRange(0.0, 200.0).optionalFieldOf("tilt", 30.0).forGetter(TerracesMode::tilt),
				Codec.doubleRange(32.0, 4096.0).optionalFieldOf("tilt_scale", 260.0).forGetter(TerracesMode::tiltScale),
				Codec.doubleRange(0.0, 4.0).optionalFieldOf("lobe", 1.0).forGetter(TerracesMode::lobe),
				Codec.doubleRange(0.5, 8.0).optionalFieldOf("rim_width", 1.3).forGetter(TerracesMode::rimWidth),
				Codec.doubleRange(0.0, 1.0).optionalFieldOf("falls", 0.0).forGetter(TerracesMode::falls),
				Codec.intRange(-32, 64).optionalFieldOf("lift", 0).forGetter(TerracesMode::lift),
				Codec.doubleRange(0.0, 1.0).optionalFieldOf("pool_coverage", 1.0).forGetter(TerracesMode::poolCoverage),
				Codec.doubleRange(16.0, 4096.0).optionalFieldOf("pool_scale", 180.0).forGetter(TerracesMode::poolScale),
				Codec.doubleRange(4.0, 512.0).optionalFieldOf("lobe_scale", 40.0).forGetter(TerracesMode::lobeScale),
			).apply(i) { common, step, poolDepth, tilt, tiltScale, lobe, rimWidth, falls, lift, poolCoverage, poolScale, lobeScale ->
				TerracesMode(common, step, poolDepth, tilt, tiltScale, lobe, rimWidth, falls, lift, poolCoverage, poolScale, lobeScale)
			}
		}
	}
}
