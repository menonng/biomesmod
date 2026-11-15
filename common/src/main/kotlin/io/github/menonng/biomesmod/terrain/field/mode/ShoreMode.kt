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
import kotlin.math.min

/**
 * `shore`: the beach of the Regon data pack (a fork of Tectonic, MIT), whose coast is a piece of its terrain spline:
 * the sea bed climbs from about 18 blocks down to sea level across a short stretch, and from the waterline on the land
 * lies flat, at sea level to one block above it, for the whole width of the coast. No dunes, bars, terraces or creeks:
 * a broad level strand with a clean waterline. Here the same profile is laid along the distance from the waterline of
 * the noise terrain ([ChunkFrame.shoreDistance]).
 *
 * @param berm height the strand reaches above the sea at its back (Regon: about 1)
 * @param run distance from the waterline over which the strand rises to it
 * @param shelf width of the slope from the waterline down to the sea bed
 * @param depth depth of the sea bed at the foot of that slope (Regon: about 18)
 * @param back unused by this profile (the edge of the biome blends into the land behind, see `edge_blend`)
 * @param cape how far capes and bays move the waterline, in blocks (0 = the waterline of the terrain as it is)
 * @param cove how far coves move it
 * @param scarp unused by this profile
 */
data class ShoreMode(
	override val common: Common, val berm: Double, val run: Double, val shelf: Double, val back: Double, val cape: Double, val cove: Double,
	val scarp: Double, val depth: Double,
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
			// Distance inland from the waterline, in blocks (negative = out at sea).
			var s = frame.shoreDistance(x, z)
			if (cape > 0) s += cape * noise.fbm(xd, zd, 280.0, 2)
			if (cove > 0) s += cove * noise.fbm(xd + 311, zd - 97, 72.0, 3)
			if (s <= 0) {
				// The slope of the sea bed: sea level at the waterline, [depth] down at its foot. Deeper water is left alone.
				val g = sea - 1 - depth * Noise.smoothstep(0.0, shelf, -s)
				out.ground = if (-s >= shelf) min(orig, g) else g
				out.water = frame.seaLevel
				out.surface = SAND
				out.surfaceDepth = 3
				return
			}
			if (orig < sea - 1) {
				return // a river or a pond behind the shore: it keeps its bed and its water
			}
			// The strand: level, from sea level at the waterline to [berm] above it.
			out.ground = sea + berm * Noise.smoothstep(0.0, run, s)
			out.surface = SAND
			out.surfaceDepth = 3
		}
	}

	companion object {
		private val SAND = Blocks.SAND.defaultBlockState()

		@JvmField
		val CODEC: MapCodec<ShoreMode> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Common.MAP_CODEC.forGetter(ShoreMode::common),
				Codec.doubleRange(0.0, 12.0).optionalFieldOf("berm", 1.0).forGetter(ShoreMode::berm),
				Codec.doubleRange(4.0, 200.0).optionalFieldOf("run", 24.0).forGetter(ShoreMode::run),
				Codec.doubleRange(0.0, 200.0).optionalFieldOf("shelf", 44.0).forGetter(ShoreMode::shelf),
				Codec.doubleRange(4.0, 200.0).optionalFieldOf("back", 40.0).forGetter(ShoreMode::back),
				Codec.doubleRange(0.0, 200.0).optionalFieldOf("cape", 0.0).forGetter(ShoreMode::cape),
				Codec.doubleRange(0.0, 100.0).optionalFieldOf("cove", 0.0).forGetter(ShoreMode::cove),
				Codec.doubleRange(0.0, 1.0).optionalFieldOf("scarp", 0.35).forGetter(ShoreMode::scarp),
				Codec.doubleRange(1.0, 40.0).optionalFieldOf("depth", 18.0).forGetter(ShoreMode::depth),
			).apply(i) { common, berm, run, shelf, back, cape, cove, scarp, depth -> ShoreMode(common, berm, run, shelf, back, cape, cove, scarp, depth) }
		}
	}
}
