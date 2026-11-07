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
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * `yardangs`: wind-carved ridges (the kaluts of Dasht-e Lut): long, streamlined, steep-sided ridges all aligned with
 * the wind, with blunt upwind heads and tapering tails, separated by flat sandy corridors.
 *
 * @param height max ridge height above the corridor floor
 * @param spacing corridor-to-corridor distance across the wind
 * @param length typical ridge length along the wind
 * @param width ridge share of the spacing (0..1)
 */
data class YardangsMode(
	override val common: Common, val height: Double, val spacing: Double, val length: Double, val width: Double,
	val direction: Double, val baseOffset: Int,
) : ShaperMode {
	override val type: ShaperMode.Type get() = ShaperMode.Type.YARDANGS

	override fun createShaper(seed: Long): Shaper = S(seed)

	private inner class S(seed: Long) : Shaper(seed) {
		private val cosD = cos(Math.toRadians(direction))
		private val sinD = sin(Math.toRadians(direction))

		override fun defaultCliffSlope(): Int = 3

		override fun defaultPalette(): FieldPalette = P.palette(P.layers(Blocks.SAND, 2), P.layers(Blocks.SAND, 2),
			P.strata(Blocks.SANDSTONE, 2, Blocks.TERRACOTTA, 3, Blocks.SMOOTH_SANDSTONE, 2, "brown_terracotta", 1), 3)

		override fun prepare(frame: ChunkFrame) {
			frame.prepareBase(6)
		}

		override fun column(frame: ChunkFrame, x: Int, z: Int, i: Int, out: Column) {
			val xd = x.toDouble()
			val zd = z.toDouble()
			val base = max(frame.seaLevel + 1.0, floor(frame.blurBase(x, z) + 0.5)) + baseOffset
			val u = xd * cosD + zd * sinD // along the wind
			val v = -xd * sinD + zd * cosD + 0.45 * spacing * noise.fbm(u, xd * 0.3, length * 1.6, 2)
			val lane = floor(v / spacing)
			val fv = v / spacing - lane // 0..1 across the lane
			val li = lane.toInt()
			var halfW = width * 0.5 * (0.55 + 0.9 * noise.cell01(li, 13)) * (0.8 + 0.4 * noise.fbm01(xd, zd, 22.0, 2))
			if (noise.cell01(li, 17) < 0.18) {
				halfW = 0.0 // some lanes are open corridors
			}
			val cross = abs(fv - 0.5)
			var h = 0.0
			if (cross < halfW) {
				// Each lane holds a chain of ridges; a ridge runs from a blunt head to a tapering tail.
				val segLen = length * (0.7 + 0.6 * noise.cell01(li, 29))
				val su = u / segLen + noise.cell01(li, 31)
				val seg = floor(su)
				val t = su - seg // 0 = upwind head, 1 = downwind tail
				val gap = 0.12 + 0.12 * noise.cell01(li * 7 + seg.toInt(), 37)
				if (t > gap) {
					val tt = (t - gap) / (1 - gap)
					val along = min(1.0, tt * 7) * (1 - tt).pow(0.55) // blunt head, long taper
					val profile = 1 - (cross / halfW).pow(3.0) // steep sides, rounded top
					val hs = height * (0.55 + 0.45 * noise.cell01(li * 13 + seg.toInt(), 41))
					h = hs * along * profile
				}
			}
			out.ground = base + h
		}
	}

	companion object {
		@JvmField
		val CODEC: MapCodec<YardangsMode> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Common.MAP_CODEC.forGetter(YardangsMode::common),
				Codec.doubleRange(2.0, 120.0).optionalFieldOf("height", 22.0).forGetter(YardangsMode::height),
				Codec.doubleRange(8.0, 512.0).optionalFieldOf("spacing", 34.0).forGetter(YardangsMode::spacing),
				Codec.doubleRange(16.0, 2048.0).optionalFieldOf("length", 160.0).forGetter(YardangsMode::length),
				Codec.doubleRange(0.1, 0.9).optionalFieldOf("width", 0.42).forGetter(YardangsMode::width),
				Codec.doubleRange(-360.0, 360.0).optionalFieldOf("direction", -20.0).forGetter(YardangsMode::direction),
				Codec.intRange(-32, 64).optionalFieldOf("base_offset", 0).forGetter(YardangsMode::baseOffset),
			).apply(i) { common, height, spacing, length, width, direction, baseOffset ->
				YardangsMode(common, height, spacing, length, width, direction, baseOffset)
			}
		}
	}
}
