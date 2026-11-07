package io.github.menonng.biomesmod.terrain.field.mode

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.menonng.biomesmod.terrain.field.ChunkFrame
import io.github.menonng.biomesmod.terrain.field.Column
import io.github.menonng.biomesmod.terrain.field.Common
import io.github.menonng.biomesmod.terrain.field.Noise
import io.github.menonng.biomesmod.terrain.field.Shaper
import io.github.menonng.biomesmod.terrain.field.ShaperMode
import kotlin.math.floor
import kotlin.math.max

/**
 * `rolling`: smooths broken terrain into wide, gently rolling ground (Mongolian steppe, Patagonian meseta). `smooth`
 * pulls the surface towards the regional height, `hills` adds long low swells, and `mesa_step` > 0 cuts the land into
 * broad flat tablelands with short escarpments (Patagonian mesetas). `lift` raises the land; with `lift_ramp` 0 it
 * rises straight from the shore as a cliff (Moher), with `lift_ramp` N the lift grows over the first N blocks of
 * original height above the sea (a plateau that slopes up from the coast, Puna).
 */
data class RollingMode(
	override val common: Common, val smooth: Double, val hills: Double, val hillScale: Double, val lift: Int, val mesaStep: Int,
	val liftRamp: Int,
) : ShaperMode {
	override val type: ShaperMode.Type get() = ShaperMode.Type.ROLLING

	override fun createShaper(seed: Long): Shaper = S(seed)

	private inner class S(seed: Long) : Shaper(seed) {
		override fun fullSurface(): Boolean = false

		override fun waterPolicy(): WaterPolicy = WaterPolicy.KEEP

		override fun prepare(frame: ChunkFrame) {
			frame.prepareBase(5)
		}

		override fun column(frame: ChunkFrame, x: Int, z: Int, i: Int, out: Column) {
			val regional = frame.blurBase(x, z)
			val orig = frame.orig(i).toDouble()
			val lifted = if (liftRamp > 0) lift * Noise.smoothstep(frame.seaLevel.toDouble(), (frame.seaLevel + liftRamp).toDouble(), orig) else lift.toDouble()
			var g = Noise.lerp(orig, regional, smooth) + lifted + hills * noise.fbm(x.toDouble(), z.toDouble(), hillScale, 3)
			if (mesaStep > 0) {
				val warped = g + 3 * noise.fbm(x.toDouble(), z.toDouble(), 40.0, 2)
				val k = floor(warped / mesaStep)
				val fr = warped / mesaStep - k
				g = (k + Noise.smoothstep(0.82, 1.0, fr)) * mesaStep // flat tread, short riser
			}
			out.ground = max(g, frame.seaLevel + 1.0)
			if (orig < frame.seaLevel && regional < frame.seaLevel) {
				out.blend = 0.0 // never fill the sea or large lakes
			}
			// A narrow river or pond in land (regional surface above the sea) is lifted with the land around it; keeping
			// it would leave a one-block slot canyon as deep as the lift.
		}
	}

	companion object {
		@JvmField
		val CODEC: MapCodec<RollingMode> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Common.MAP_CODEC.forGetter(RollingMode::common),
				Codec.doubleRange(0.0, 1.0).optionalFieldOf("smooth", 0.8).forGetter(RollingMode::smooth),
				Codec.doubleRange(0.0, 40.0).optionalFieldOf("hills", 5.0).forGetter(RollingMode::hills),
				Codec.doubleRange(16.0, 4096.0).optionalFieldOf("hill_scale", 180.0).forGetter(RollingMode::hillScale),
				Codec.intRange(-32, 64).optionalFieldOf("lift", 0).forGetter(RollingMode::lift),
				Codec.intRange(0, 40).optionalFieldOf("mesa_step", 0).forGetter(RollingMode::mesaStep),
				Codec.intRange(0, 64).optionalFieldOf("lift_ramp", 0).forGetter(RollingMode::liftRamp),
			).apply(i) { common, smooth, hills, hillScale, lift, mesaStep, liftRamp -> RollingMode(common, smooth, hills, hillScale, lift, mesaStep, liftRamp) }
		}
	}
}
