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
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign

/**
 * `u_valley`: a glacial trough. Flat floor, near-vertical walls, high shoulders, and waterfalls that drop from the rim
 * (Lauterbrunnen's 72 falls). With `flood_to_sea` the floor sinks below sea level and fills to sea level: a fjord
 * (Geirangerfjord, Seven Sisters falling straight into the water).
 *
 * @param shoulder rim height above the regional terrain
 * @param floorDepth floor below the regional terrain (dry valley) or below sea level (fjord)
 * @param width rim-to-rim width
 * @param floorWidth flat floor width
 * @param steepness wall exponent: higher = more vertical (2 = U, 6 = cliffs)
 * @param falls waterfalls per 100 blocks of wall (0 = none)
 */
data class UValleyMode(
	override val common: Common, val shoulder: Int, val floorDepth: Int, val width: Double, val floorWidth: Double, val steepness: Double,
	val direction: Double, val spacing: Double, val falls: Double, val floodToSea: Boolean,
) : ShaperMode {
	override val type: ShaperMode.Type get() = ShaperMode.Type.U_VALLEY

	override fun createShaper(seed: Long): Shaper = S(seed)

	private inner class S(seed: Long) : Shaper(seed) {
		// Glacial troughs are straighter than rivers: small meander amplitude, long wavelength.
		private val line = Meander(noise.fork(3), direction, spacing, spacing * 0.2, width * 3.2)

		override fun defaultCliffSlope(): Int = 4

		override fun fullSurface(): Boolean = false

		override fun waterPolicy(): WaterPolicy = if (floodToSea) WaterPolicy.REPLACE else WaterPolicy.KEEP

		override fun defaultPalette(): FieldPalette = P.palette(null, P.layers(Blocks.GRAVEL, 2),
			P.strata(Blocks.STONE, 6, Blocks.ANDESITE, 2, Blocks.STONE, 4, Blocks.TUFF, 1), 4)

		override fun prepare(frame: ChunkFrame) {
			frame.prepareBase(5)
		}

		override fun column(frame: ChunkFrame, x: Int, z: Int, i: Int, out: Column) {
			val xd = x.toDouble()
			val zd = z.toDouble()
			val regional = frame.blurBase(x, z)
			val rim = regional + shoulder + 0.5 * (frame.orig(i) - regional)
			val floorY = if (floodToSea) (frame.seaLevel - floorDepth).toDouble() else regional - floorDepth
			// Shapers are shared by every worldgen thread, so the scratch array is per call.
			val res = DoubleArray(3)
			line.eval(xd, zd, res)
			val d = abs(res[0])
			val half = width * 0.5 * (0.6 + 0.8 * noise.fbm01(xd, zd, 220.0, 2))
			val fh = min(half - 4, floorWidth * 0.5)
			val g: Double = if (d <= fh) {
				floorY + 0.8 * noise.fbm(xd, zd, 70.0, 1)
			} else if (d < half) {
				val t = (d - fh) / (half - fh)
				// Steep U: the wall rises almost vertically, with a little curvature at the toe and the lip.
				var u = Noise.smoothstep(0.0, 1.0, t).pow(1.0 / steepness)
				u = min(1.0, u + 0.04 * noise.fbm(xd, zd, 18.0, 2))
				floorY + (rim - floorY) * u
			} else {
				rim
			}
			out.ground = g
			if (floodToSea && d < half) {
				out.water = frame.seaLevel
			}
			// Waterfalls: columns at the wall foot carry a fall from the rim, at intervals along the valley.
			if (falls > 0 && d > fh - 1 && d < fh + 2.5) {
				val along = res[1]
				val side = sign(res[0])
				val cell = 100.0 / falls
				val k = floor(along / cell)
				val at = (k + 0.2 + 0.6 * noise.cell01(k.toInt(), if (side > 0) 7 else 11)) * cell
				if (abs(along - at) < 0.6 && noise.cell01(k.toInt(), if (side > 0) 3 else 5) > 0.35) {
					val rimY = floor(rim).toInt()
					out.flags = out.flags or Column.FALL or Column.NO_WALL
					out.fallTop = rimY - 1
					out.ground = min(g, floorY)
				}
			}
		}
	}

	companion object {
		@JvmField
		val CODEC: MapCodec<UValleyMode> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Common.MAP_CODEC.forGetter(UValleyMode::common),
				Codec.intRange(0, 160).optionalFieldOf("shoulder", 50).forGetter(UValleyMode::shoulder),
				Codec.intRange(0, 120).optionalFieldOf("floor_depth", 10).forGetter(UValleyMode::floorDepth),
				Codec.doubleRange(24.0, 800.0).optionalFieldOf("width", 110.0).forGetter(UValleyMode::width),
				Codec.doubleRange(4.0, 400.0).optionalFieldOf("floor_width", 40.0).forGetter(UValleyMode::floorWidth),
				Codec.doubleRange(1.0, 12.0).optionalFieldOf("steepness", 5.0).forGetter(UValleyMode::steepness),
				Codec.doubleRange(-360.0, 360.0).optionalFieldOf("direction", 0.0).forGetter(UValleyMode::direction),
				Codec.doubleRange(128.0, 4096.0).optionalFieldOf("spacing", 600.0).forGetter(UValleyMode::spacing),
				Codec.doubleRange(0.0, 20.0).optionalFieldOf("falls", 1.0).forGetter(UValleyMode::falls),
				Codec.BOOL.optionalFieldOf("flood_to_sea", false).forGetter(UValleyMode::floodToSea),
			).apply(i) { common, shoulder, floorDepth, width, floorWidth, steepness, direction, spacing, falls, floodToSea ->
				UValleyMode(common, shoulder, floorDepth, width, floorWidth, steepness, direction, spacing, falls, floodToSea)
			}
		}
	}
}
