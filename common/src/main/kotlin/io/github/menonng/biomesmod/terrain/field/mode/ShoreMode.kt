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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * `shore`: an eroded sandy coast, built as a cross-shore profile rather than as noise. Every column gets a rough
 * distance from the waterline (the regional surface relative to the sea, bent into capes, bays and coves, with tidal
 * creeks bitten into it), and the ground follows a beach profile along that distance:
 *
 * a shallow sand shelf under water with bars and spits that break the surface, a drop-off at its outer edge; the
 * foreshore climbing in long one-block terraces to the berm, gently in some stretches and as a low sand scarp cut by
 * the sea in others; behind the berm low dunes that run out into the land that was there.
 *
 * @param berm height of the beach crest above the sea
 * @param run width of the foreshore, from the waterline to the crest
 * @param shelf width of the shallow shelf off the beach
 * @param back width of the backshore, over which the beach runs out into the original land
 * @param cape how far capes and bays move the waterline, in blocks
 * @param cove how far coves move it
 * @param scarp share of the shore that is cut into a low scarp instead of a gentle slope
 */
data class ShoreMode(
	override val common: Common, val berm: Double, val run: Double, val shelf: Double, val back: Double, val cape: Double, val cove: Double,
	val scarp: Double,
) : ShaperMode {
	override val type: ShaperMode.Type get() = ShaperMode.Type.SHORE

	override fun createShaper(seed: Long): Shaper = S(seed)

	private inner class S(seed: Long) : Shaper(seed) {
		override fun fullSurface(): Boolean = false

		override fun waterPolicy(): WaterPolicy = WaterPolicy.REPLACE

		override fun defaultPalette(): FieldPalette = P.palette(P.layers(Blocks.GRASS_BLOCK, 1, Blocks.DIRT, 3), P.layers(Blocks.SAND, 3, Blocks.SANDSTONE, 2),
			P.strata(Blocks.STONE, 4, Blocks.ANDESITE, 1), 3)

		override fun prepare(frame: ChunkFrame) {
			frame.prepareBase(7)
			frame.prepareShore()
		}

		override fun column(frame: ChunkFrame, x: Int, z: Int, i: Int, out: Column) {
			val xd = x.toDouble()
			val zd = z.toDouble()
			val sea = frame.seaLevel.toDouble()
			val orig = frame.orig(i).toDouble()
			// Distance inland from the waterline, in blocks (negative = out at sea), bent into capes, bays and coves.
			val far = frame.shoreDistance(x, z)
			var s = far + cape * noise.fbm(xd, zd, 280.0, 2) + cove * noise.fbm(xd + 311, zd - 97, 72.0, 3)
			// Tidal creeks and narrow coves bitten into the beach, along the thin lines of a ridged noise.
			val bite = Noise.smoothstep(0.86, 0.97, 1 - abs(noise.fbm(xd - 800, zd + 450, 150.0, 2)))
			s -= bite * run * 1.1 * Noise.smoothstep(run * 1.8, run * 0.3, s)
			if (s <= 0) {
				val off = -s
				var g = if (off < shelf) {
					sea - 1.3 - 1.4 * Noise.smoothstep(0.0, shelf, off) + 0.5 * noise.fbm(xd, zd, 11.0, 1)
				} else {
					// The edge of the shelf: a step down, then the bed falls away.
					sea - 4.8 - min(12.0, (off - shelf) * 0.45)
				}
				// Bars and spits: sand ridges on the shelf that break the surface and half close a lagoon behind them.
				val bar = Noise.smoothstep(0.9, 0.98, 1 - abs(noise.fbm(xd + 90, zd + 1300, 95.0, 2))) *
					Noise.smoothstep(shelf * 1.2, shelf * 0.6, off) * Noise.smoothstep(2.0, 7.0, off)
				g += (sea + 1.2 - g) * bar
				// Never fill deep water: beyond the shelf the sea bed is only ever lowered.
				out.ground = if (off >= shelf && bar <= 0) min(orig, g) else g
				out.water = frame.seaLevel
				out.surface = SAND
				out.surfaceDepth = 3
				return
			}
			if (orig < sea - 1) {
				return // a river or a pond behind the shore: it keeps its bed and its water
			}
			// How the sea has treated this stretch: 0 a gentle beach, 1 a cut scarp.
			val cut = Noise.smoothstep(1 - scarp - 0.12, 1 - scarp + 0.12, noise.fbm01(xd + 2000, zd - 2000, 190.0, 2))
			val gentle = berm * min(1.0, s / run).pow(0.8)
			val steep = min(berm, 0.6 + s * 0.8)
			var h = gentle + (steep - gentle) * cut
			// Long flat treads with short risers: the terraces sand takes on a block grid.
			val k = floor(h)
			h = max(1.0, k + Noise.smoothstep(0.7, 1.0, h - k))
			var g = sea + h
			if (s > run) {
				// Low dunes behind the crest, then the beach runs out into the land.
				g += 1.8 * noise.fbm01(xd, zd, 38.0, 2) * Noise.smoothstep(run, run + 12, s)
				g += (orig - g) * Noise.smoothstep(run + back * 0.3, run + back, s)
			}
			out.ground = g
			// The sand ends in a ragged line, so grass and sand interfinger at the back of the beach. Behind it the beach
			// biome is a grown-over coastal plain, with blowouts of bare sand that thin out inland: the sand belongs to the
			// shore, not to the whole width of the biome.
			val limit = run * (0.85 + 0.3 * noise.fbm(xd, zd, 23.0, 2))
			val blowout = noise.fbm(xd + 50, zd - 90, 13.0, 2) > 0.25 + 0.45 * Noise.smoothstep(limit, limit + back, s)
			if (s < limit || blowout) {
				out.surface = SAND
				out.surfaceDepth = 3
			} else {
				out.surface = GRASS
				out.surfaceDepth = 1
			}
		}
	}

	companion object {
		private val SAND = Blocks.SAND.defaultBlockState()
		private val GRASS = Blocks.GRASS_BLOCK.defaultBlockState()

		@JvmField
		val CODEC: MapCodec<ShoreMode> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Common.MAP_CODEC.forGetter(ShoreMode::common),
				Codec.doubleRange(1.0, 12.0).optionalFieldOf("berm", 4.0).forGetter(ShoreMode::berm),
				Codec.doubleRange(4.0, 200.0).optionalFieldOf("run", 34.0).forGetter(ShoreMode::run),
				Codec.doubleRange(0.0, 200.0).optionalFieldOf("shelf", 26.0).forGetter(ShoreMode::shelf),
				Codec.doubleRange(4.0, 200.0).optionalFieldOf("back", 40.0).forGetter(ShoreMode::back),
				Codec.doubleRange(0.0, 200.0).optionalFieldOf("cape", 30.0).forGetter(ShoreMode::cape),
				Codec.doubleRange(0.0, 100.0).optionalFieldOf("cove", 14.0).forGetter(ShoreMode::cove),
				Codec.doubleRange(0.0, 1.0).optionalFieldOf("scarp", 0.35).forGetter(ShoreMode::scarp),
			).apply(i) { common, berm, run, shelf, back, cape, cove, scarp -> ShoreMode(common, berm, run, shelf, back, cape, cove, scarp) }
		}
	}
}
