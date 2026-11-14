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
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * `sea_cliff`: a coast where the land ends in a wall (Alkefjellet, the Causeway Coast, Moher). The shoreline is drawn
 * from the real one, bent into headlands and coves; seaward of it the ground is cut down below the sea, landward it is
 * raised to a cliff top that stands `height` blocks straight out of the water. The top rolls gently and rises inland.
 * The face is broken by `ledges` narrow shelves (nesting ledges, the steps of old lava flows), and a shallow rock
 * `shelf` of that many blocks lies at the cliff foot before the sea bed drops away.
 *
 * @param height cliff height above the sea
 * @param ledges number of shelves on the face (0 = sheer)
 * @param indent how far headlands and coves depart from the real shore, in blocks
 * @param shelf width of the shallow shelf at the cliff foot
 * @param inland extra height gained inland, per 100 blocks from the cliff edge
 */
data class SeaCliffMode(
	override val common: Common, val height: Int, val ledges: Int, val indent: Double, val shelf: Double, val inland: Double,
	val top: java.util.Optional<net.minecraft.world.level.block.state.BlockState>,
) : ShaperMode {
	override val type: ShaperMode.Type get() = ShaperMode.Type.SEA_CLIFF

	override fun createShaper(seed: Long): Shaper = S(seed)

	private inner class S(seed: Long) : Shaper(seed) {
		override fun fullSurface(): Boolean = true

		override fun defaultCliffSlope(): Int = 3

		override fun waterPolicy(): WaterPolicy = WaterPolicy.REPLACE

		override fun defaultPalette(): FieldPalette = P.palette(null, P.layers("gravel", 2), P.strata("stone", 5, "andesite", 2, "stone", 4, "tuff", 1), 3)

		override fun prepare(frame: ChunkFrame) {
			frame.prepareBase(6)
		}

		override fun column(frame: ChunkFrame, x: Int, z: Int, i: Int, out: Column) {
			val xd = x.toDouble()
			val zd = z.toDouble()
			val sea = frame.seaLevel.toDouble()
			// Signed distance to the shore in blocks, roughly: the regional surface relative to the sea (a coast slopes at
			// about one in four), bent by broad and fine noise into headlands, coves and geos.
			val s = (frame.blurBase(x, z) - sea - 7.0) * 5.0 + 110.0 * noise.fbm(xd + 900, zd + 400, 520.0, 2) + indent * noise.fbm(xd, zd, 150.0, 3) + indent * 0.25 * noise.fbm(xd + 71, zd - 13, 37.0, 2)
			if (s <= 0) {
				// Sea: a shallow shelf of rock at the cliff foot, then the bed falls away.
				val off = -s
				val depth = if (off < shelf) 1.5 + 1.5 * noise.fbm01(xd, zd, 9.0, 1) else 3.0 + min(14.0, (off - shelf) * 0.35)
				out.ground = min(frame.orig(i).toDouble(), sea - depth)
				out.water = frame.seaLevel
				return
			}
			// Land: the top of the cliff, a little uneven, climbing gently inland.
			val topH = height * (0.8 + 0.4 * noise.fbm01(xd, zd, 220.0, 2)) + inland * min(3.0, s / 100.0) + 2.0 * noise.fbm(xd, zd, 60.0, 2)
			val face = max(2.0, ledges * 2.5 + 2) // horizontal run of the whole face
			var g = sea + topH
			if (s < face) {
				val t = s / face
				if (ledges > 0) {
					// Risers with narrow treads: each ledge is a couple of blocks deep.
					val steps = ledges + 1.0
					val k = floor(t * steps)
					val fr = t * steps - k
					g = sea + topH * (k + min(1.0, fr / 0.3).pow(0.5)) / steps
				} else {
					g = sea + topH * t.pow(0.25)
				}
			}
			out.ground = max(g, sea + 1.0)
			if (top.isPresent && s >= face) {
				out.surface = top.get()
				out.surfaceDepth = 1
			}
		}
	}

	companion object {
		@JvmField
		val CODEC: MapCodec<SeaCliffMode> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Common.MAP_CODEC.forGetter(SeaCliffMode::common),
				Codec.intRange(4, 120).optionalFieldOf("height", 36).forGetter(SeaCliffMode::height),
				Codec.intRange(0, 8).optionalFieldOf("ledges", 2).forGetter(SeaCliffMode::ledges),
				Codec.doubleRange(0.0, 200.0).optionalFieldOf("indent", 46.0).forGetter(SeaCliffMode::indent),
				Codec.doubleRange(0.0, 64.0).optionalFieldOf("shelf", 8.0).forGetter(SeaCliffMode::shelf),
				Codec.doubleRange(0.0, 40.0).optionalFieldOf("inland", 6.0).forGetter(SeaCliffMode::inland),
				net.minecraft.world.level.block.state.BlockState.CODEC.optionalFieldOf("top").forGetter(SeaCliffMode::top),
			).apply(i) { common, height, ledges, indent, shelf, inland, top -> SeaCliffMode(common, height, ledges, indent, shelf, inland, top) }
		}
	}
}
