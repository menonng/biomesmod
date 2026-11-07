package io.github.menonng.biomesmod.terrain.field.mode

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.menonng.biomesmod.terrain.field.ChunkFrame
import io.github.menonng.biomesmod.terrain.field.Column
import io.github.menonng.biomesmod.terrain.field.Common
import io.github.menonng.biomesmod.terrain.field.FieldPalette
import io.github.menonng.biomesmod.terrain.field.Shaper
import io.github.menonng.biomesmod.terrain.field.ShaperMode
import net.minecraft.world.level.block.Blocks
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot

/**
 * `glacier`: rivers of ice filling the valley floors of mountain terrain (Aletsch, Grey Glacier, Cordillera Darwin).
 * The ice surface is a heavily smoothed copy of the terrain lowered by `drop`: wherever the real ground lies below it,
 * the valley fills with ice; ridges and peaks stand clear. Dark medial-moraine stripes run along the ice, and crevasse
 * fields open where it steepens. Above `snowline` the ice turns to firn (snow).
 */
data class GlacierMode(
	override val common: Common, val drop: Int, val blur: Int, val moraineSpacing: Double, val snowline: Int, val crevasses: Crevasses,
) : ShaperMode {
	override val type: ShaperMode.Type get() = ShaperMode.Type.GLACIER

	override fun createShaper(seed: Long): Shaper = S(seed)

	private inner class S(seed: Long) : Shaper(seed) {
		override fun fullSurface(): Boolean = false

		override fun waterPolicy(): WaterPolicy = WaterPolicy.REPLACE

		override fun defaultPalette(): FieldPalette = P.palette(P.layers(Blocks.PACKED_ICE, 3), P.layers(Blocks.PACKED_ICE, 2),
			P.strata(Blocks.PACKED_ICE, 4, Blocks.BLUE_ICE, 1), 5)

		override fun prepare(frame: ChunkFrame) {
			frame.prepareBase(blur)
		}

		override fun column(frame: ChunkFrame, x: Int, z: Int, i: Int, out: Column) {
			val xd = x.toDouble()
			val zd = z.toDouble()
			val ice = frame.blurBase(x, z) - drop + 1.2 * noise.fbm(xd, zd, 64.0, 2)
			val o = frame.orig(i)
			if (o >= ice - 0.5) {
				out.blend = 0.0 // rock above the ice: leave it
				return
			}
			var g = ice
			val crack = crevasses.depthAt(noise, xd, zd, 0.1)
			if (crack > 0.5) {
				g -= crack
			}
			out.ground = g
			out.flags = out.flags or Column.FORCE_DRESS
			if (g >= snowline) {
				out.surface = Blocks.SNOW_BLOCK.defaultBlockState()
				out.surfaceDepth = 2
			} else if (crack > 0.5) {
				out.surface = Blocks.BLUE_ICE.defaultBlockState()
				out.surfaceDepth = 2
			} else if (moraineSpacing > 0) {
				// Medial moraines: thin, wavy dark stripes following the direction of the slope.
				val gx = frame.blurBase(x + 8, z) - frame.blurBase(x - 8, z)
				val gz = frame.blurBase(x, z + 8) - frame.blurBase(x, z - 8)
				val len = hypot(gx, gz)
				if (len > 0.5) {
					val across = (-gz * x + gx * z) / len + 6 * noise.fbm(xd, zd, 90.0, 2)
					val fr = abs(across / moraineSpacing - floor(across / moraineSpacing + 0.5)) * moraineSpacing
					if (fr < 1.2 + noise.fbm01(xd, zd, 25.0, 1)) {
						out.surface = MORAINE
						out.surfaceDepth = 1
					}
				}
			}
		}
	}

	companion object {
		private val MORAINE = Blocks.GRAVEL.defaultBlockState()

		@JvmField
		val CODEC: MapCodec<GlacierMode> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Common.MAP_CODEC.forGetter(GlacierMode::common),
				Codec.intRange(0, 120).optionalFieldOf("drop", 6).forGetter(GlacierMode::drop),
				Codec.intRange(1, 8).optionalFieldOf("blur", 6).forGetter(GlacierMode::blur),
				Codec.doubleRange(0.0, 512.0).optionalFieldOf("moraine_spacing", 48.0).forGetter(GlacierMode::moraineSpacing),
				Codec.intRange(-64, 320).optionalFieldOf("snowline", 150).forGetter(GlacierMode::snowline),
				Crevasses.CODEC.optionalFieldOf("crevasses", Crevasses(6.0, 1.1, 9, 0.3, 90.0, 14.0)).forGetter(GlacierMode::crevasses),
			).apply(i) { common, drop, blur, moraineSpacing, snowline, crevasses -> GlacierMode(common, drop, blur, moraineSpacing, snowline, crevasses) }
		}
	}
}
