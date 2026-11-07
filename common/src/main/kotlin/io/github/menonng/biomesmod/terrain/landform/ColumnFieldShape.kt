package io.github.menonng.biomesmod.terrain.landform

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.menonng.biomesmod.terrain.util.StrataPalette
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * `column_field`: a field of polygonal basalt columns of varied heights that steps down from the land into the sea
 * (Giant's Causeway). Columns are Voronoi cells (mostly 5-7 sided at this scale), each with its own height; the field
 * is an irregular lobe that runs downhill and out under the water.
 *
 * @param cell column width in blocks (2-3 reads best)
 * @param length how far the lobe runs (blocks)
 * @param width lobe width
 * @param rise height of the landward end above sea level
 * @param jitter per-column height variation (blocks)
 */
class ColumnFieldShape internal constructor(
	internal val cell: Float,
	internal val length: Span,
	internal val width: Span,
	internal val rise: Span,
	internal val jitter: Float,
) : LandformShape() {
	override fun name(): String = NAME

	override fun maxReach(): Int = ceil(length.max * 0.6 + width.max + 4).toInt()

	override fun defaultPalette(): StrataPalette = B.strata("polished_basalt", 1, "basalt", 4, "smooth_basalt", 1)

	override fun defaultBaseRange(): Span = Span.of(-6.0, 6.0) // at the shoreline

	override fun create(site: Site): Landform? {
		val r = site.rng
		// Run towards the lowest neighbouring floor: down the beach into the sea.
		var best = Int.MAX_VALUE
		var dir = 0.0
		for (k in 0 until 8) {
			val a = Math.PI * 2 * k / 8
			val f = site.floor(site.x + cos(a) * 30, site.z + sin(a) * 30)
			if (f < best) {
				best = f
				dir = a
			}
		}
		if (best >= site.sea - 1) {
			return null // needs water on one side
		}
		return Instance(site, dir + r.range(-0.3, 0.3), r.range(length), r.range(width), r.range(rise).toInt())
	}

	internal inner class Instance(s: Site, private val dir: Double, private val len: Double, private val wid: Double, private val riseH: Int) :
		Landform(s, maxReach()) {

		override fun column(x: Int, z: Int, c: Col) {
			val px = x + 0.5
			val pz = z + 0.5
			val dx = px - cx
			val dz = pz - cz
			val u = dx * cos(dir) + dz * sin(dir) // seaward
			val v = -dx * sin(dir) + dz * cos(dir)
			val t = (u + len * 0.4) / len // 0 landward end, 1 seaward tip
			if (t < 0 || t > 1) {
				return
			}
			val halfW = wid * 0.5 * sin(Math.PI * min(1.0, t * 1.3 + 0.1)) * (1 + 0.3 * LNoise.noise1(seed xor 0xB1, u / 12))
			if (abs(v) > halfW) {
				return
			}
			// One height per column (cell); cells via jittered grid.
			val gx = px / cell
			val gz = pz / cell
			val ci = LNoise.floor(gx)
			val cj = LNoise.floor(gz)
			var bestD = Double.MAX_VALUE
			var cellHash = 0L
			for (dj in -1..1) {
				for (di in -1..1) {
					val h = LNoise.hash(seed xor 0xB2, ci + di, cj + dj)
					val fx = ci + di + 0.5 + (LNoise.unit(h) - 0.5) * 0.8
					val fz = cj + dj + 0.5 + (LNoise.unit(h * 31 + 7) - 0.5) * 0.8
					val dd = (fx - gx) * (fx - gx) + (fz - gz) * (fz - gz)
					if (dd < bestD) {
						bestD = dd
						cellHash = h
					}
				}
			}
			val edgeFade = min(1.0, (halfW - abs(v)) / 4)
			val level = sea - 2 + (riseH + 2) * (1 - t).pow(1.3) * edgeFade
			c.top = Math.round(level + jitter * (LNoise.unit(cellHash * 17 + 3) - 0.5) * 2).toInt()
			c.kind = K_BODY
		}

		override fun block(x: Int, y: Int, z: Int, depth: Int, c: Col, wet: Boolean): BlockState =
			if (depth == 0) Blocks.POLISHED_BASALT.defaultBlockState() else Blocks.BASALT.defaultBlockState()
	}

	companion object {
		internal const val NAME = "column_field"

		internal val CODEC: MapCodec<ColumnFieldShape> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Codec.floatRange(1.2f, 6f).optionalFieldOf("cell", 2.2f).forGetter { it.cell },
				Span.CODEC.optionalFieldOf("length", Span.of(36.0, 60.0)).forGetter { it.length },
				Span.CODEC.optionalFieldOf("width", Span.of(16.0, 26.0)).forGetter { it.width },
				Span.CODEC.optionalFieldOf("rise", Span.of(6.0, 12.0)).forGetter { it.rise },
				Codec.floatRange(0f, 6f).optionalFieldOf("jitter", 2f).forGetter { it.jitter },
			).apply(i) { cell, length, width, rise, jitter -> ColumnFieldShape(cell, length, width, rise, jitter) }
		}
	}
}
