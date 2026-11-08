package io.github.menonng.biomesmod.terrain.field.mode

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.menonng.biomesmod.terrain.field.ChunkFrame
import io.github.menonng.biomesmod.terrain.field.Column
import io.github.menonng.biomesmod.terrain.field.Common
import io.github.menonng.biomesmod.terrain.field.Shaper
import io.github.menonng.biomesmod.terrain.field.ShaperMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * `depression`: ground lowered below sea level and kept dry (polders, basins). The biome edge becomes a dike: an
 * embankment `dike_height` above sea level whose crest wanders with noise and follows the biome outline, so no water
 * can reach the dry floor. Inside, the floor sits `depth` blocks under sea level with gentle relief and optional
 * drainage ditches (water held well below the surrounding ground).
 *
 * @param depth how far the floor sits under sea level (blocks)
 * @param relief floor undulation amplitude (blocks)
 * @param dikeHeight crest height above sea level; 0 = no dike (only safe far from water)
 * @param dikeWidth crest-to-toe half width in blocks
 * @param ditchSpacing blocks between drainage ditches (0 = none); ditches follow gently curving lines
 * @param ditchDepth ditch depth below the floor; its water stays 1 block below the floor
 */
data class DepressionMode(
	override val common: Common, val depth: Int, val relief: Double, val dikeHeight: Int, val dikeWidth: Int,
	val ditchSpacing: Double, val ditchDepth: Int,
) : ShaperMode {
	override val type: ShaperMode.Type get() = ShaperMode.Type.DEPRESSION

	override fun createShaper(seed: Long): Shaper = S(seed)

	private inner class S(seed: Long) : Shaper(seed) {
		/**
		 * The crest sits where the edge factor is guaranteed >= ChunkFrame.WET_F (0.5) whatever the edge jitter, because
		 * below that the frame keeps original water: inside the crest everything must be dry.
		 */
		private fun crestAt(): Int = max(dikeWidth + 1, ceil((common.edgeNoise + 0.5) * common.edgeBlend).toInt() + 1)

		override fun edgeReach(): Int = crestAt() + dikeWidth * 3 + 8

		override fun fullSurface(): Boolean = false // keep the meadow / forest floor blocks the column already had

		override fun column(frame: ChunkFrame, x: Int, z: Int, i: Int, out: Column) {
			val sea = frame.seaLevel
			val xd = x.toDouble()
			val zd = z.toDouble()
			val bed = sea - depth + relief * noise.fbm(xd, zd, 96.0, 2)
			val d = frame.edgeDistance(i)
			val crestAt = crestAt()
			if (dikeHeight > 0) {
				// Full reshaping right behind the dike so the edge fade never leaves sea water inside.
				out.blend = if (d >= crestAt) 1.0 else 0.0
				val crest = sea + dikeHeight + 0.6 * noise.fbm(xd, zd, 40.0, 1)
				// Where the land outside is higher than the dike there is nothing to hold back: slope down from it to the
				// floor over the edge blend width instead of cutting a wall at the crest.
				val o = frame.orig(i).toDouble()
				if (d >= crestAt && o > crest + 1) {
					val k = ((d - crestAt) / max(6, common.edgeBlend).toDouble()).coerceIn(0.0, 1.0)
					val s = k * k * (3 - 2 * k)
					out.ground = max(bed, o + (bed - o) * s)
					return
				}
				val t = abs(d - crestAt) / dikeWidth
				if (t < 1) {
					val h = crest - (crest - bed) * t * t // rounded embankment profile
					out.minGround = max(sea + 0.0, h)
					out.ground = max(bed, h)
					return
				}
			}
			out.ground = bed
			if (ditchSpacing > 0 && d > crestAt + dikeWidth + 2) {
				val u = (xd + 9 * noise.simplex(xd / 180.0, zd / 180.0)) / ditchSpacing
				val fu = abs(u - floor(u + 0.5)) * ditchSpacing
				if (fu < 0.8) {
					val bedY = floor(bed + 0.5).toInt()
					out.ground = (bedY - ditchDepth).toDouble()
					out.water = bedY // water surface one block under the surrounding floor
				}
			}
		}
	}

	companion object {
		@JvmField
		val CODEC: MapCodec<DepressionMode> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Common.MAP_CODEC.forGetter(DepressionMode::common),
				Codec.intRange(1, 40).optionalFieldOf("depth", 6).forGetter(DepressionMode::depth),
				Codec.doubleRange(0.0, 8.0).optionalFieldOf("relief", 1.2).forGetter(DepressionMode::relief),
				Codec.intRange(0, 16).optionalFieldOf("dike_height", 2).forGetter(DepressionMode::dikeHeight),
				Codec.intRange(2, 16).optionalFieldOf("dike_width", 4).forGetter(DepressionMode::dikeWidth),
				Codec.doubleRange(0.0, 512.0).optionalFieldOf("ditch_spacing", 0.0).forGetter(DepressionMode::ditchSpacing),
				Codec.intRange(1, 6).optionalFieldOf("ditch_depth", 2).forGetter(DepressionMode::ditchDepth),
			).apply(i) { common, depth, relief, dikeHeight, dikeWidth, ditchSpacing, ditchDepth ->
				DepressionMode(common, depth, relief, dikeHeight, dikeWidth, ditchSpacing, ditchDepth)
			}
		}
	}
}
