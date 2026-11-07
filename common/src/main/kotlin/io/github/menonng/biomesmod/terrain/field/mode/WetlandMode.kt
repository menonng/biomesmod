package io.github.menonng.biomesmod.terrain.field.mode

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.menonng.biomesmod.terrain.field.ChunkFrame
import io.github.menonng.biomesmod.terrain.field.Column
import io.github.menonng.biomesmod.terrain.field.Common
import io.github.menonng.biomesmod.terrain.field.Coverage
import io.github.menonng.biomesmod.terrain.field.FieldPalette
import io.github.menonng.biomesmod.terrain.field.Noise
import io.github.menonng.biomesmod.terrain.field.Shaper
import io.github.menonng.biomesmod.terrain.field.ShaperMode
import net.minecraft.world.level.block.Blocks
import kotlin.math.min

/**
 * `wetland`: land pulled down to a water table and broken into shallow open water, low hummocks (tree islands) and a
 * branching maze of channels (Everglades sawgrass "river of grass", Amazon várzea flooded forest).
 *
 * @param waterOffset water table relative to sea level
 * @param dryCoverage fraction of the ground that rises just above the water (hummocks, levees)
 * @param hummock max hummock height above the water
 * @param shallow depth of the open water between hummocks
 * @param channels channel coverage (0 = none); channels are narrow, branching, slightly deeper
 */
data class WetlandMode(
	override val common: Common, val waterOffset: Int, val dryCoverage: Double, val hummock: Double, val shallow: Int,
	val channels: Double, val scale: Double,
) : ShaperMode {
	override val type: ShaperMode.Type get() = ShaperMode.Type.WETLAND

	override fun createShaper(seed: Long): Shaper = S(seed)

	private inner class S(seed: Long) : Shaper(seed) {
		private val dryT = Coverage.threshold(dryCoverage, 4)

		override fun defaultPalette(): FieldPalette = P.palette(P.layers(Blocks.GRASS_BLOCK, 1, Blocks.DIRT, 2), P.layers(Blocks.MUD, 2, Blocks.CLAY, 1),
			P.strata(Blocks.DIRT, 3, Blocks.CLAY, 1), 1024)

		override fun column(frame: ChunkFrame, x: Int, z: Int, i: Int, out: Column) {
			val w = frame.seaLevel + waterOffset // water fills Y < w
			val n = noise.fbm(x.toDouble(), z.toDouble(), scale, 4)
			var g = if (n > dryT) {
				w + min(hummock, (n - dryT) * hummock * 2.2) // rises gently out of the water
			} else {
				w - 1 - shallow * Noise.smoothstep(dryT, dryT - 0.35, n)
			}
			if (channels > 0) {
				val ridge = noise.ridged(x + 811.0, z - 377.0, scale * 1.8, 3)
				val t = 1 - channels * 0.08
				if (ridge > t) {
					g = min(g, (w - 2 - shallow).toDouble())
				}
			}
			out.ground = g
			out.water = w
		}
	}

	companion object {
		@JvmField
		val CODEC: MapCodec<WetlandMode> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Common.MAP_CODEC.forGetter(WetlandMode::common),
				Codec.intRange(-16, 32).optionalFieldOf("water_offset", 0).forGetter(WetlandMode::waterOffset),
				Codec.doubleRange(0.0, 1.0).optionalFieldOf("dry_coverage", 0.45).forGetter(WetlandMode::dryCoverage),
				Codec.doubleRange(0.0, 12.0).optionalFieldOf("hummock", 2.0).forGetter(WetlandMode::hummock),
				Codec.intRange(1, 6).optionalFieldOf("shallow", 1).forGetter(WetlandMode::shallow),
				Codec.doubleRange(0.0, 1.0).optionalFieldOf("channels", 0.25).forGetter(WetlandMode::channels),
				Codec.doubleRange(8.0, 1024.0).optionalFieldOf("scale", 70.0).forGetter(WetlandMode::scale),
			).apply(i) { common, waterOffset, dryCoverage, hummock, shallow, channels, scale ->
				WetlandMode(common, waterOffset, dryCoverage, hummock, shallow, channels, scale)
			}
		}
	}
}
