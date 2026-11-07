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
import kotlin.math.pow

/**
 * `islands`: a shallow sea scattered with islands of every size: an archipelago (Exuma Cays), or, with low `height`
 * and small `scale`, an eroded tidal coast of sandbars, islets and tide pools. Island outlines come from
 * domain-warped multi-octave noise, so they are lobed and varied, with sand rims and shoals around them; `rocky`
 * turns some islands into steep rock outcrops instead.
 *
 * @param coverage fraction of the area above water
 * @param scale typical island size (noise scale, blocks)
 * @param height highest island top above sea level
 * @param beach height band above the sea that is sand
 * @param depth sea depth far from any island (the bank floor)
 * @param rocky fraction of islands that are rocky
 */
data class IslandsMode(
	override val common: Common, val coverage: Double, val scale: Double, val height: Double, val beach: Int, val depth: Int,
	val rocky: Double,
) : ShaperMode {
	override val type: ShaperMode.Type get() = ShaperMode.Type.ISLANDS

	override fun createShaper(seed: Long): Shaper = S(seed)

	private inner class S(seed: Long) : Shaper(seed) {
		private val t = Coverage.threshold(coverage, 4)

		override fun defaultPalette(): FieldPalette = P.palette(P.layers(Blocks.GRASS_BLOCK, 1, Blocks.DIRT, 3), P.layers(Blocks.SAND, 3, Blocks.SANDSTONE, 2),
			P.strata(Blocks.STONE, 4, Blocks.ANDESITE, 1), 3)

		override fun column(frame: ChunkFrame, x: Int, z: Int, i: Int, out: Column) {
			val sea = frame.seaLevel
			val wx = x + scale * 0.35 * noise.fbm(x.toDouble(), z.toDouble(), scale * 1.3, 2)
			val wz = z + scale * 0.35 * noise.fbm(x + 913.0, z - 377.0, scale * 1.3, 2)
			val n = noise.fbm(wx, wz, scale, 4)
			out.water = sea
			if (n > t) {
				val e = min(1.0, (n - t) / (Coverage.std(4) * 1.6))
				val rock = noise.fbm(wx + 4091, wz - 2203, scale * 1.4, 1) > Coverage.threshold(rocky, 1)
				val h = if (rock) height * 1.6 * e.pow(0.45) else height * e.pow(1.2)
				out.ground = sea + h
				if (rock) {
					out.surface = STONE
					out.surfaceDepth = 2
				} else if (h < beach + 0.5) {
					out.surface = SAND
					out.surfaceDepth = 3
				}
			} else {
				// Shoals: shallow near islands, the bank floor further out, with sandy ripples.
				val s = Noise.smoothstep(t, t - 0.45, n)
				out.ground = sea - 1 - depth * s + 0.6 * noise.fbm(x.toDouble(), z.toDouble(), 14.0, 2) * s
			}
		}
	}

	companion object {
		private val SAND = Blocks.SAND.defaultBlockState()
		private val STONE = Blocks.STONE.defaultBlockState()

		@JvmField
		val CODEC: MapCodec<IslandsMode> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Common.MAP_CODEC.forGetter(IslandsMode::common),
				Codec.doubleRange(0.0, 1.0).optionalFieldOf("coverage", 0.22).forGetter(IslandsMode::coverage),
				Codec.doubleRange(8.0, 2048.0).optionalFieldOf("scale", 70.0).forGetter(IslandsMode::scale),
				Codec.doubleRange(1.0, 80.0).optionalFieldOf("height", 9.0).forGetter(IslandsMode::height),
				Codec.intRange(0, 12).optionalFieldOf("beach", 2).forGetter(IslandsMode::beach),
				Codec.intRange(1, 40).optionalFieldOf("depth", 6).forGetter(IslandsMode::depth),
				Codec.doubleRange(0.0, 1.0).optionalFieldOf("rocky", 0.15).forGetter(IslandsMode::rocky),
			).apply(i) { common, coverage, scale, height, beach, depth, rocky -> IslandsMode(common, coverage, scale, height, beach, depth, rocky) }
		}
	}
}
