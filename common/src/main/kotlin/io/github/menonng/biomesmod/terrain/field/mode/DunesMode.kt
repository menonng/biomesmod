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
import net.minecraft.world.level.block.state.BlockState
import java.util.Optional
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * `dunes`: a dune sea over a flat interdune floor.
 * - `transverse`: sinuous crest lines across the wind with a gentle windward side and a steep slip face (Lençóis
 *   Maranhenses). Crests break up and rejoin, they are never continuous rings or crescents.
 * - `linear`: long seif ridges along the wind, symmetric.
 * - `star`: tall pyramidal dunes with radiating arms (Namib / Sossusvlei).
 *
 * `lagoon_depth` floods the interdune floor up to that height, so rain lagoons fill every hollow between crests.
 * `pan_coverage` flattens patches into clay pans of `pan_block` (Deadvlei).
 *
 * @param level absolute interdune floor Y; default max(sea + 1, regional terrain height) + base_offset
 */
data class DunesMode(
	override val common: Common, val style: String, val height: Double, val wavelength: Double, val direction: Double,
	val level: Optional<Int>, val baseOffset: Int, val lagoonDepth: Int, val panCoverage: Double, val panBlock: Optional<BlockState>,
) : ShaperMode {
	override val type: ShaperMode.Type get() = ShaperMode.Type.DUNES

	override fun createShaper(seed: Long): Shaper = S(seed)

	private inner class S(seed: Long) : Shaper(seed) {
		private val cosD = cos(Math.toRadians(direction))
		private val sinD = sin(Math.toRadians(direction))
		private val panT = Coverage.threshold(panCoverage, 3)
		private val pan: BlockState = panBlock.orElseGet { P.id("white_terracotta") }

		override fun defaultPalette(): FieldPalette = P.palette(P.layers(Blocks.SAND, 4, Blocks.SANDSTONE, 3), P.layers(Blocks.SAND, 3),
			P.strata(Blocks.SANDSTONE, 3, Blocks.SMOOTH_SANDSTONE, 1), 1024)

		override fun prepare(frame: ChunkFrame) {
			frame.prepareBase(if (level.isPresent) 0 else 6)
		}

		private fun floorAt(frame: ChunkFrame, x: Int, z: Int): Double {
			if (level.isPresent) {
				return (level.get() + baseOffset).toDouble()
			}
			return max(frame.seaLevel + 1.0, floor(frame.blurBase(x, z) + 0.5)) + baseOffset
		}

		/** Dune height above the interdune floor, >= 0. */
		private fun dune(x: Double, z: Double): Double {
			val u = x * cosD + z * sinD // along the wind
			val v = -x * sinD + z * cosD // across the wind
			val lam = wavelength
			val amp = height * (0.55 + 0.45 * noise.fbm01(x, z, lam * 3.5, 2))
			return when (style) {
				"star" -> {
					val r = noise.ridged(x, z, lam, 3)
					val peak = noise.fbm01(x + 701, z - 409, lam * 0.9, 2)
					amp * r.pow(2.2) * (0.35 + 0.65 * peak)
				}
				"linear" -> {
					val p = v / lam + 0.45 * noise.fbm(u / (lam * 6), v / (lam * 3), 1.0, 2)
					val fr = p - floor(p)
					val prof = 1 - abs(fr - 0.5) * 2
					val run = Noise.smoothstep(-0.35, 0.15, noise.fbm(u, v, lam * 5, 2))
					amp * prof.pow(1.6) * run
				}
				else -> {
					val p = u / lam + 0.55 * noise.fbm(v / (lam * 2.2), u / (lam * 4.0), 1.0, 2)
					val fr = p - floor(p)
					val a = 0.72 // windward share of the wavelength
					var prof = if (fr < a) fr / a else (1 - fr) / (1 - a)
					prof = prof * prof * (3 - 2 * prof)
					val breakup = Noise.smoothstep(-0.85, -0.25, noise.fbm(x - 1201, z + 557, lam * 1.7, 2))
					amp * prof * breakup
				}
			}
		}

		override fun column(frame: ChunkFrame, x: Int, z: Int, i: Int, out: Column) {
			val xd = x.toDouble()
			val zd = z.toDouble()
			var base = floorAt(frame, x, z)
			// Interdune floor relief: only the deeper hollows hold lagoons, the rest is damp sand.
			val floorRelief = if (lagoonDepth > 0) 1.0 + 1.6 * noise.fbm(xd + 77, zd - 91, wavelength * 1.3, 2) else 0.0
			var h = dune(xd, zd) + max(0.0, floorRelief)
			if (panCoverage > 0) {
				val pm = noise.fbm(xd + 2203, zd + 3109, wavelength * 4, 3)
				val t = Noise.smoothstep(panT, panT + 0.12, pm)
				h *= 1 - t
				if (t > 0.6) {
					out.surface = pan
					out.surfaceDepth = 2
					base -= 1
				}
			}
			out.ground = base + h
			if (lagoonDepth > 0) {
				out.water = floor(base + 0.5).toInt() + lagoonDepth
			}
		}
	}

	companion object {
		@JvmField
		val CODEC: MapCodec<DunesMode> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				Common.MAP_CODEC.forGetter(DunesMode::common),
				Codec.STRING.optionalFieldOf("style", "transverse").forGetter(DunesMode::style),
				Codec.doubleRange(1.0, 120.0).optionalFieldOf("height", 10.0).forGetter(DunesMode::height),
				Codec.doubleRange(8.0, 1024.0).optionalFieldOf("wavelength", 40.0).forGetter(DunesMode::wavelength),
				Codec.doubleRange(-360.0, 360.0).optionalFieldOf("direction", 20.0).forGetter(DunesMode::direction),
				Codec.INT.optionalFieldOf("level").forGetter(DunesMode::level),
				Codec.intRange(-32, 64).optionalFieldOf("base_offset", 0).forGetter(DunesMode::baseOffset),
				Codec.intRange(0, 12).optionalFieldOf("lagoon_depth", 0).forGetter(DunesMode::lagoonDepth),
				Codec.doubleRange(0.0, 1.0).optionalFieldOf("pan_coverage", 0.0).forGetter(DunesMode::panCoverage),
				BlockState.CODEC.optionalFieldOf("pan_block").forGetter(DunesMode::panBlock),
			).apply(i) { common, style, height, wavelength, direction, level, baseOffset, lagoonDepth, panCoverage, panBlock ->
				DunesMode(common, style, height, wavelength, direction, level, baseOffset, lagoonDepth, panCoverage, panBlock)
			}
		}
	}
}
