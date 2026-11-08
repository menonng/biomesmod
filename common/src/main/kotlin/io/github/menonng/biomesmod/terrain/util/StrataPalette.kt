package io.github.menonng.biomesmod.terrain.util

import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.world.level.block.state.BlockState
import java.util.Optional
import kotlin.math.floor

/**
 * Shared rock palette for engine features: a surface cap plus horizontal strata repeating by world Y. Real rock is not
 * one block per layer, so every layer can be a weighted mix of blocks (clustered by 3D noise, speckled per block), the
 * layer boundaries undulate (`warp`) and neighbouring layers interleave near their boundaries (`mix`).
 *
 * ```
 * "palette": {
 *   "top": {"Name": "minecraft:grass_block"},            // optional, exposed upward-facing surface
 *   "top_depth": 1,                                         // optional, default 1
 *   "strata": [                                             // repeats every sum(thickness) blocks of Y
 *     {"block": {"Name": "minecraft:red_terracotta"}, "thickness": 4},
 *     {"blocks": [{"block": {"Name": "minecraft:terracotta"}, "weight": 3},
 *                 {"block": {"Name": "minecraft:brown_terracotta"}, "weight": 1}], "thickness": 3}
 *   ],
 *   "strata_offset": 0,                                     // optional Y shift of the pattern
 *   "warp": 2.5, "warp_scale": 40,                          // optional undulation of the layer boundaries (blocks)
 *   "mix": 0.3                                              // optional interleaving of neighbouring layers (0..1)
 * }
 * ```
 */
data class StrataPalette(
	val top: Optional<BlockState>,
	val topDepth: Int,
	val strata: List<Layer>,
	val strataOffset: Int,
	val warp: Double = DEFAULT_WARP,
	val warpScale: Double = 40.0,
	val mix: Double = DEFAULT_MIX,
) {
	/** One weighted block of a layer. */
	data class Weighted(val block: BlockState, val weight: Int) {
		companion object {
			val CODEC: Codec<Weighted> = RecordCodecBuilder.create { i ->
				i.group(
					BlockState.CODEC.fieldOf("block").forGetter(Weighted::block),
					Codec.intRange(1, 1000).optionalFieldOf("weight", 1).forGetter(Weighted::weight),
				).apply(i) { block, weight -> Weighted(block, weight) }
			}
		}
	}

	data class Layer(val blocks: List<Weighted>, val thickness: Int) {
		constructor(block: BlockState, thickness: Int) : this(listOf(Weighted(block, 1)), thickness)

		/** The main block of the layer (the heaviest). */
		val block: BlockState get() = blocks.maxBy { it.weight }.block
		private val total = blocks.sumOf { it.weight }

		fun pick(u: Double): BlockState {
			if (blocks.size == 1) {
				return blocks[0].block
			}
			var r = (u * total).toInt().coerceIn(0, total - 1)
			for (w in blocks) {
				r -= w.weight
				if (r < 0) {
					return w.block
				}
			}
			return blocks.last().block
		}

		companion object {
			private val ONE: Codec<Layer> = RecordCodecBuilder.create { i ->
				i.group(
					BlockState.CODEC.fieldOf("block").forGetter(Layer::block),
					Codec.intRange(1, 256).optionalFieldOf("thickness", 1).forGetter(Layer::thickness),
				).apply(i) { block, thickness -> Layer(block, thickness) }
			}
			private val MANY: Codec<Layer> = RecordCodecBuilder.create { i ->
				i.group(
					Weighted.CODEC.listOf().fieldOf("blocks").forGetter(Layer::blocks),
					Codec.intRange(1, 256).optionalFieldOf("thickness", 1).forGetter(Layer::thickness),
				).apply(i) { blocks, thickness -> Layer(blocks, thickness) }
			}

			@JvmField
			val CODEC: Codec<Layer> = Codec.either(ONE, MANY).xmap(
				{ e -> e.map({ it }, { it }) },
				{ l -> if (l.blocks.size == 1) Either.left(l) else Either.right(l) })
		}
	}

	private val period: Int = strata.sumOf { it.thickness }

	/** Main block of the layer at world height y (no position: used where only the band matters). */
	fun strataAt(y: Int): BlockState = layerAt(Math.floorMod(y + strataOffset, period)).first.block

	/** Rock at a world position: undulating layer boundaries, interleaved edges, clustered mix within the layer. */
	fun strataAt(x: Int, y: Int, z: Int): BlockState {
		val yy = y + strataOffset + warp * (valueNoise(x / warpScale, 0.0, z / warpScale, 11) * 2 - 1) +
			0.6 * warp * (valueNoise(x / (warpScale * 0.31), 0.0, z / (warpScale * 0.31), 12) * 2 - 1)
		val k = Math.floorMod(floor(yy).toInt(), period)
		val (layer, index, pos) = layerAt3(k)
		var chosen = layer
		if (mix > 0 && strata.size > 1) {
			val h = hash(x, y, z, 21)
			if (pos == 0 && h < mix) {
				chosen = strata[Math.floorMod(index - 1, strata.size)]
			} else if (pos == layer.thickness - 1 && h < mix * 0.6) {
				chosen = strata[(index + 1) % strata.size]
			}
		}
		val u = 0.62 * valueNoise(x / 4.5, y / 2.5, z / 4.5, 31) + 0.38 * hash(x, y, z, 37)
		return chosen.pick(u.coerceIn(0.0, 0.9999))
	}

	/**
	 * Block for a solid position at height y that lies [depthBelowSurface] blocks under the exposed surface of its
	 * column (0 = the surface block itself).
	 */
	fun blockAt(x: Int, y: Int, z: Int, depthBelowSurface: Int): BlockState =
		if (top.isPresent && depthBelowSurface < topDepth) top.get() else strataAt(x, y, z)

	fun blockAt(y: Int, depthBelowSurface: Int): BlockState =
		if (top.isPresent && depthBelowSurface < topDepth) top.get() else strataAt(y)

	private fun layerAt(k: Int): Pair<Layer, Int> {
		var r = k
		for (layer in strata) {
			if (r < layer.thickness) {
				return layer to r
			}
			r -= layer.thickness
		}
		return strata.first() to 0
	}

	private fun layerAt3(k: Int): Triple<Layer, Int, Int> {
		var r = k
		for ((i, layer) in strata.withIndex()) {
			if (r < layer.thickness) {
				return Triple(layer, i, r)
			}
			r -= layer.thickness
		}
		return Triple(strata.first(), 0, 0)
	}

	companion object {
		const val DEFAULT_WARP = 1.6
		const val DEFAULT_MIX = 0.25

		@JvmField
		val CODEC: Codec<StrataPalette> = RecordCodecBuilder.create { i ->
			i.group(
				BlockState.CODEC.optionalFieldOf("top").forGetter(StrataPalette::top),
				Codec.intRange(0, 16).optionalFieldOf("top_depth", 1).forGetter(StrataPalette::topDepth),
				Layer.CODEC.listOf().fieldOf("strata").forGetter(StrataPalette::strata),
				Codec.INT.optionalFieldOf("strata_offset", 0).forGetter(StrataPalette::strataOffset),
				Codec.doubleRange(0.0, 32.0).optionalFieldOf("warp", DEFAULT_WARP).forGetter(StrataPalette::warp),
				Codec.doubleRange(4.0, 1024.0).optionalFieldOf("warp_scale", 40.0).forGetter(StrataPalette::warpScale),
				Codec.doubleRange(0.0, 1.0).optionalFieldOf("mix", DEFAULT_MIX).forGetter(StrataPalette::mix),
			).apply(i) { top, topDepth, strata, offset, warp, warpScale, mix -> StrataPalette(top, topDepth, strata, offset, warp, warpScale, mix) }
		}

		private fun hashInt(x: Int, y: Int, z: Int, salt: Int): Int {
			var h = x * 0x27D4EB2D xor (y * 0x165667B1) xor (z * 0x1B873593) xor (salt * 0x68E31DA4)
			h = h xor (h ushr 15)
			h *= 0x2C1B3C6D
			h = h xor (h ushr 12)
			h *= 0x297A2D39
			return h xor (h ushr 15)
		}

		/** Uniform [0, 1) per block. */
		fun hash(x: Int, y: Int, z: Int, salt: Int): Double = (hashInt(x, y, z, salt) ushr 8) / 16777216.0

		/** Smooth value noise in [0, 1] (lattice values interpolated with a smoothstep). */
		fun valueNoise(x: Double, y: Double, z: Double, salt: Int): Double {
			val ix = floor(x).toInt()
			val iy = floor(y).toInt()
			val iz = floor(z).toInt()
			val fx = x - ix
			val fy = y - iy
			val fz = z - iz
			val sx = fx * fx * (3 - 2 * fx)
			val sy = fy * fy * (3 - 2 * fy)
			val sz = fz * fz * (3 - 2 * fz)
			fun v(dx: Int, dy: Int, dz: Int) = hash(ix + dx, iy + dy, iz + dz, salt)
			val x00 = v(0, 0, 0) + (v(1, 0, 0) - v(0, 0, 0)) * sx
			val x10 = v(0, 1, 0) + (v(1, 1, 0) - v(0, 1, 0)) * sx
			val x01 = v(0, 0, 1) + (v(1, 0, 1) - v(0, 0, 1)) * sx
			val x11 = v(0, 1, 1) + (v(1, 1, 1) - v(0, 1, 1)) * sx
			val y0 = x00 + (x10 - x00) * sy
			val y1 = x01 + (x11 - x01) * sy
			return y0 + (y1 - y0) * sz
		}
	}
}
