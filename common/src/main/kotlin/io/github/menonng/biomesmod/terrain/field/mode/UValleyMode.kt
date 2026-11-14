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
 * `u_valley`: glacial troughs cut into the mountains that are already there (the mode never raises the ground, so the
 * biome needs a mountain site to cut into). The trunk floor lies `floor_depth` below the level the site's mountains
 * stand on and stays level while the ranges above rise and fall; tributary troughs are smaller and hang above it;
 * the width swells and pinches along the valley. Flat floor, near-vertical walls, and waterfalls that drop from the rim
 * (Lauterbrunnen's 72 falls). With `flood_to_sea` the floor sinks below sea level and fills to sea level: a fjord
 * (Geirangerfjord, Seven Sisters falling straight into the water).
 *
 * @param shoulder height of the trough wall above the trunk floor; above it the side goes on up as a mountainside
 * @param floorDepth floor below the site's base level (dry valley) or below sea level (fjord)
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
		private val line = Drainage(noise.fork(3), direction, spacing)

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
			// Shapers are shared by every worldgen thread, so the scratch array is per call.
			val res = DoubleArray(3)
			line.eval(xd, zd, res)
			val cut = Noise.smoothstep(0.08, 0.2, res[2])
			if (cut <= 0) {
				return // no ice ran here: the mountain stays as it is
			}
			val d = abs(res[0])
			val natural = frame.orig(i).toDouble()
			// The trunk floor lies a little below the level the site's mountains stand on; without a site, below the
			// regional surface.
			val siteY = frame.siteTargetY()
			val level = if (siteY.isNaN()) frame.blurBase(x, z) else siteY
			val floorBase = if (floodToSea) (frame.seaLevel - floorDepth).toDouble() else level - floorDepth
			// Tributary glaciers cut smaller troughs whose floors hang above the trunk valley.
			val size = Noise.clamp((res[2] - 0.1) / 0.9, 0.0, 1.0)
			val sf = 0.4 + 0.6 * size
			// The trough swells into basins and pinches at rock bars along its length.
			val half = width * 0.5 * sf * (0.55 + 0.9 * noise.fbm01(xd, zd, 260.0, 2))
			val fh = min(half - 4, floorWidth * 0.5 * sf * (0.6 + 0.8 * noise.fbm01(xd + 300, zd - 700, 180.0, 2)))
			val wall = shoulder * (0.55 + 0.45 * size)
			val floorY = if (floodToSea) floorBase else floorBase + shoulder * 0.4 * (1 - size) + 1.2 * noise.fbm(xd, zd, 70.0, 1)
			val trough: Double = if (d <= fh) {
				floorY
			} else if (d < half) {
				val t = (d - fh) / (half - fh)
				// Steep U: the wall rises almost vertically, with a little curvature at the toe and the lip.
				var u = Noise.smoothstep(0.0, 1.0, t).pow(1.0 / steepness)
				u = min(1.0, u + 0.04 * noise.fbm(xd, zd, 18.0, 2))
				floorY + wall * u
			} else {
				// Above the trough edge the valley side goes on up at the slope of a scree-covered mountainside.
				floorY + wall + (d - half) * 0.9
			}
			// Only ever cut: where the land is already lower than the trough (a pass, the lowland beyond the site) the
			// valley simply opens out.
			if (trough >= natural) {
				return
			}
			val g = natural + (trough - natural) * cut
			out.ground = g
			if (floodToSea && d < half) {
				out.water = frame.seaLevel
			}
			// Waterfalls: columns at the wall foot carry a fall from the trough edge, at intervals along the valley.
			if (falls > 0 && d > fh - 1 && d < fh + 2.5 && frame.blurBase(x, z) > floorY + 12) {
				val along = res[1]
				val side = if (noise.cell01(x shr 5, z shr 5, 9) > 0.5) 1.0 else -1.0
				val cell = 100.0 / falls
				val k = floor(along / cell)
				val at = (k + 0.2 + 0.6 * noise.cell01(k.toInt(), if (side > 0) 7 else 11)) * cell
				if (abs(along - at) < 0.6 && noise.cell01(k.toInt(), if (side > 0) 3 else 5) > 0.35) {
					val rimY = floor(min(floorY + wall, frame.blurBase(x, z))).toInt()
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
