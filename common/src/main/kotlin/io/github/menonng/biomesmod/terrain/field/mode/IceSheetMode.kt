package io.github.menonng.biomesmod.terrain.field.mode

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.menonng.biomesmod.terrain.field.ChunkFrame
import io.github.menonng.biomesmod.terrain.field.Column
import io.github.menonng.biomesmod.terrain.field.Common
import io.github.menonng.biomesmod.terrain.field.FieldPalette
import io.github.menonng.biomesmod.terrain.field.Noise
import io.github.menonng.biomesmod.terrain.field.Shaper
import io.github.menonng.biomesmod.terrain.field.ShaperMode
import net.minecraft.world.level.block.Blocks
import kotlin.math.max
import kotlin.math.min

/**
 * `ice_sheet`: an ice cap that buries the land under a smooth dome of ice (Vostok plateau, Franz Josef Land caps),
 * and, over the sea, an ice shelf floating on the original water with a sheer marine cliff where it meets open water
 * (Ross Ice Shelf). Crevasse fields open on steeper ice and near the margins.
 *
 * @param thickness ice above the land surface in the interior
 * @param dome extra height in the interior (rises over `dome_scale` blocks from the edge)
 * @param shelf true: also cover water with a floating shelf
 * @param freeboard shelf top above sea level
 * @param crevasses crevasse field settings (coverage 0 = none)
 */
data class IceSheetMode(
	override val common: Common, val thickness: Int, val dome: Int, val domeScale: Double, val shelf: Boolean, val freeboard: Int,
	val roughness: Double, val crevasses: Crevasses,
) : ShaperMode {
	override val type: ShaperMode.Type get() = ShaperMode.Type.ICE_SHEET

	override fun createShaper(seed: Long): Shaper = S(seed)

	private inner class S(seed: Long) : Shaper(seed) {
		override fun edgeReach(): Int = min(240.0, domeScale).toInt()

		override fun waterPolicy(): WaterPolicy = WaterPolicy.KEEP

		override fun defaultCliffSlope(): Int = 4

		override fun defaultPalette(): FieldPalette = P.palette(P.layers(Blocks.SNOW_BLOCK, 2, Blocks.PACKED_ICE, 3), P.layers(Blocks.PACKED_ICE, 2),
			P.strata(Blocks.PACKED_ICE, 5, Blocks.BLUE_ICE, 1, Blocks.PACKED_ICE, 3), 4)

		override fun prepare(frame: ChunkFrame) {
			frame.prepareBase(6)
		}

		override fun column(frame: ChunkFrame, x: Int, z: Int, i: Int, out: Column) {
			val sea = frame.seaLevel
			val xd = x.toDouble()
			val zd = z.toDouble()
			val inner = Noise.smoothstep(0.0, domeScale, frame.edgeDistance(i))
			val rough = roughness * noise.fbm(xd, zd, 48.0, 3)
			val water = frame.origWater(i) != ChunkFrame.NONE && frame.orig(i) < sea - 1
			var g: Double
			if (water) {
				if (!shelf) {
					return
				}
				g = sea + freeboard + dome * 0.25 * inner + rough * 0.5
				out.keepWaterBelow = sea // the shelf floats: original sea water stays under it
				// Marine cliff: full height right from the edge instead of a ramp.
				out.blend = if (frame.edgeDistance(i) > 1.5 + 2 * noise.fbm01(xd, zd, 30.0, 1)) 1.0 else 0.0
			} else {
				val surface = max(frame.blurBase(x, z), frame.orig(i) - 4.0)
				g = surface + thickness + dome * inner + rough
			}
			val crack = crevasses.depthAt(noise, xd, zd, 0.25 * (1 - inner))
			if (crack > 0.5) {
				g -= crack
				out.surface = Blocks.BLUE_ICE.defaultBlockState()
				out.surfaceDepth = 2
			}
			out.ground = g
		}
	}

	companion object {
		@JvmField
		val CODEC: MapCodec<IceSheetMode> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Common.MAP_CODEC.forGetter(IceSheetMode::common),
				Codec.intRange(0, 160).optionalFieldOf("thickness", 14).forGetter(IceSheetMode::thickness),
				Codec.intRange(0, 160).optionalFieldOf("dome", 20).forGetter(IceSheetMode::dome),
				Codec.doubleRange(16.0, 4096.0).optionalFieldOf("dome_scale", 300.0).forGetter(IceSheetMode::domeScale),
				Codec.BOOL.optionalFieldOf("shelf", false).forGetter(IceSheetMode::shelf),
				Codec.intRange(1, 60).optionalFieldOf("freeboard", 12).forGetter(IceSheetMode::freeboard),
				Codec.doubleRange(0.0, 8.0).optionalFieldOf("roughness", 1.2).forGetter(IceSheetMode::roughness),
				Crevasses.CODEC.optionalFieldOf("crevasses", Crevasses(8.0, 1.3, 10, 0.1, 160.0, 30.0)).forGetter(IceSheetMode::crevasses),
			).apply(i) { common, thickness, dome, domeScale, shelf, freeboard, roughness, crevasses ->
				IceSheetMode(common, thickness, dome, domeScale, shelf, freeboard, roughness, crevasses)
			}
		}
	}
}
