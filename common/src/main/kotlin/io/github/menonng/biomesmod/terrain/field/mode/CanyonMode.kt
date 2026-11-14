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

/**
 * `canyon`: a plateau cut by a deep, meandering canyon with stepped walls (alternating cliffs and talus benches, like
 * the Grand Canyon's rock layers), shorter side canyons that deepen towards the main one, and a river on the floor.
 * Centre lines come from [Meander]: they wander but never loop back into rings or crescents.
 *
 * @param plateau plateau height above the regional terrain
 * @param depth main canyon depth below the plateau
 * @param width main canyon rim-to-rim width
 * @param floorWidth flat floor width
 * @param benches number of cliff/bench steps in each wall
 * @param spacing distance between parallel main canyons
 * @param sideSpacing distance between side canyons (0 = none)
 * @param riverWidth river width on the floor (0 = dry)
 */
data class CanyonMode(
	override val common: Common, val plateau: Int, val depth: Int, val width: Double, val floorWidth: Double, val benches: Int,
	val direction: Double, val spacing: Double, val sideSpacing: Double, val riverWidth: Double, val domes: Double,
) : ShaperMode {
	override val type: ShaperMode.Type get() = ShaperMode.Type.CANYON

	override fun createShaper(seed: Long): Shaper = S(seed)

	private inner class S(seed: Long) : Shaper(seed) {
		private val drain = Drainage(noise.fork(1), direction, spacing)

		override fun fullSurface(): Boolean = false // the plateau keeps its own surface; only cut columns get the palette

		override fun defaultCliffSlope(): Int = 3

		override fun defaultPalette(): FieldPalette = P.palette(null, P.layers(Blocks.SAND, 1, Blocks.RED_SANDSTONE, 2),
			P.strata("red_terracotta", 5, "orange_terracotta", 3, Blocks.TERRACOTTA, 4, "white_terracotta", 2,
				"brown_terracotta", 4, Blocks.RED_SANDSTONE, 3, "light_gray_terracotta", 2), 3)

		override fun prepare(frame: ChunkFrame) {
			frame.prepareBase(6)
		}

		/** Stepped wall profile: 0 at the floor edge, 1 at the rim, cliffs separated by gently sloping benches. */
		private fun wall(t0: Double, x: Double, z: Double): Double {
			val t = Noise.clamp(t0, 0.0, 1.0)
			val steps = benches.toDouble()
			val k = floor(t * steps)
			val fr = t * steps - k
			val cliff = 0.35 + 0.15 * noise.fbm(x, z, 60.0, 1) // share of each step that is cliff
			val within = if (fr < cliff) 0.15 * fr / cliff else 0.15 + 0.85 * Noise.smoothstep(cliff, 1.0, fr)
			return (k + within) / steps
		}

		override fun column(frame: ChunkFrame, x: Int, z: Int, i: Int, out: Column) {
			val xd = x.toDouble()
			val zd = z.toDouble()
			val tmp = DoubleArray(3) // shapers are shared by every worldgen thread, so the scratch array is per call
			val top = frame.blurBase(x, z) + plateau
			// One branching network: the trunk is the great canyon, its tributaries the side canyons, which narrow and
			// shallow towards their heads and leave buttes and promontories standing between them.
			drain.eval(xd, zd, tmp)
			val d = tmp[0]
			// Headwater gullies are left out: a canyon starts where a few of them have joined.
			val size = Noise.clamp((tmp[2] - 0.1) / 0.9, 0.0, 1.0)
			val cut = Noise.smoothstep(0.08, 0.2, tmp[2])
			val sf = (0.22 + 0.78 * size) * cut
			val half = width * 0.5 * sf * (0.8 + 0.4 * noise.fbm01(xd, zd, 300.0, 2))
			val fh = floorWidth * 0.5 * sf
			val floorY = top - depth * (0.35 + 0.65 * size) * cut * (0.85 + 0.15 * noise.fbm01(xd, zd, 400.0, 1))
			var g = top
			if (cut > 0 && d < half) {
				val t = (d - fh) / (half - fh)
				g = floorY + (top - floorY) * wall(t, xd, zd)
			}
			// The plateau is the regional surface lifted by `plateau` (faded in at the biome edge by the frame), with a
			// little of the original relief kept so it is not a table.
			if (g >= top - 0.01) {
				g = top + 0.35 * (frame.orig(i) - frame.blurBase(x, z))
				if (domes > 0) {
					// Rounded, lumpy summits that fall away towards the walls (weathered sandstone domes).
					val inward = Noise.smoothstep(half, half + 40.0, d)
					g += domes * inward * (0.35 + 0.65 * noise.fbm01(xd, zd, 46.0, 3)) - domes * 0.4 * (1 - inward)
				}
			}
			out.ground = g
			// The inner gorge: the trunk river has cut a narrow slot into the canyon floor.
			if (size > 0.55 && d < fh * 0.6 + 3) {
				val slot = depth * 0.16 * (size - 0.55) / 0.45 * (1 - Noise.smoothstep(fh * 0.25, fh * 0.6 + 3, d))
				out.ground = g - slot
				if (riverWidth > 0 && size >= 0.999 && d < riverWidth * 0.5 + 0.5) {
					val f = floor(top - depth * 1.12 + 0.5).toInt()
					out.ground = (f - 2).toDouble()
					out.water = f
				}
			}
		}
	}

	companion object {
		@JvmField
		val CODEC: MapCodec<CanyonMode> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Common.MAP_CODEC.forGetter(CanyonMode::common),
				Codec.intRange(0, 120).optionalFieldOf("plateau", 18).forGetter(CanyonMode::plateau),
				Codec.intRange(8, 200).optionalFieldOf("depth", 70).forGetter(CanyonMode::depth),
				Codec.doubleRange(16.0, 600.0).optionalFieldOf("width", 140.0).forGetter(CanyonMode::width),
				Codec.doubleRange(2.0, 400.0).optionalFieldOf("floor_width", 14.0).forGetter(CanyonMode::floorWidth),
				Codec.intRange(1, 12).optionalFieldOf("benches", 5).forGetter(CanyonMode::benches),
				Codec.doubleRange(-360.0, 360.0).optionalFieldOf("direction", 35.0).forGetter(CanyonMode::direction),
				Codec.doubleRange(128.0, 4096.0).optionalFieldOf("spacing", 520.0).forGetter(CanyonMode::spacing),
				Codec.doubleRange(0.0, 2048.0).optionalFieldOf("side_spacing", 150.0).forGetter(CanyonMode::sideSpacing),
				Codec.doubleRange(0.0, 24.0).optionalFieldOf("river_width", 4.0).forGetter(CanyonMode::riverWidth),
				Codec.doubleRange(0.0, 60.0).optionalFieldOf("domes", 0.0).forGetter(CanyonMode::domes),
			).apply(i) { common, plateau, depth, width, floorWidth, benches, direction, spacing, sideSpacing, riverWidth, domes ->
				CanyonMode(common, plateau, depth, width, floorWidth, benches, direction, spacing, sideSpacing, riverWidth, domes)
			}
		}
	}
}
