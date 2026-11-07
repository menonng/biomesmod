package io.github.menonng.biomesmod.site

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.menonng.biomesmod.compat.DensityBase
import io.github.menonng.biomesmod.compat.DensityCompat
import net.minecraft.util.KeyDispatchDataCodec
import net.minecraft.world.level.levelgen.DensityFunction
import java.util.Objects
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * `biomesmod:site`: moves the terrain of a site to the place's real elevation. Installed around the noise router of
 * every overworld-like dimension when its random state is created ([SiteRouter]), so it works with every world preset
 * (default, Large Biomes, Amplified) and with terrain mods that replace the vanilla density functions.
 *
 * The original terrain is not replaced but moved: inside a site, block height y samples the original terrain at
 * `y - delta` below the new surface (caves, ores and overhangs come along) and at a vertically compressed height above
 * it (peaks shrink by the site's detail factor, so plateaus come out flat). The original smooth surface S0 comes from
 * the router's own `depth` (its zero crossing), so the shift is smooth everywhere and fades out across the ramp.
 *
 * Modes:
 * - [Mode.FINAL]: the final density. Sampled on its own 4x8x4 corner grid and interpolated (like vanilla does), plus a
 *   flat profile near the surface for plateau and basin sites.
 * - [Mode.DEPTH]: the climate depth, so biome depth (surface vs cave biomes) follows the new surface.
 * - [Mode.INITIAL]: 1.21.1's initial density without jaggedness (preliminary surface, aquifers, surface rules).
 * - [Mode.SURFACE]: 26.2's preliminary surface level (a height, shifted by delta).
 */
class SiteDensity(
	val mode: Mode,
	val argument: DensityFunction,
	/** The original (unshifted) depth of the router; its zero crossing is the original smooth surface. */
	val depth: DensityFunction,
	val noise: DensityFunction.NoiseHolder,
	val temperature: DensityFunction,
	val vegetation: DensityFunction,
	val continents: DensityFunction,
	val erosion: DensityFunction,
	/** Site size factor (2 for Large Biomes, whose climate is stretched 4x). */
	val scale: Double,
) : DensityBase() {
	enum class Mode { FINAL, DEPTH, INITIAL, SURFACE }

	@Volatile
	private var cachedField: SiteField? = null

	/** The world's site field; only valid once the noise is wired (inside a RandomState). */
	internal fun siteField(): SiteField = cachedField ?: SiteField.of(this).also { cachedField = it }

	override fun compute(context: DensityFunction.FunctionContext): Double {
		if (noise.noise() == null) {
			return argument.compute(context)
		}
		val field = siteField()
		val x = context.blockX()
		val z = context.blockZ()
		val col = field.column(x, z, depth)
		return when (mode) {
			Mode.SURFACE -> argument.compute(context) + col.delta
			Mode.DEPTH, Mode.INITIAL -> if (!col.active) argument.compute(context)
			else argument.compute(DensityFunction.SinglePointContext(x, col.mapY(context.blockY()), z))
			Mode.FINAL -> if (!col.active) argument.compute(context) else interpolated(field, x, context.blockY(), z)
		}
	}

	/** Trilinear interpolation of the transformed final density over the 4x8x4 corner grid (per-thread cache). */
	private fun interpolated(field: SiteField, x: Int, y: Int, z: Int): Double {
		val cx = Math.floorDiv(x, CELL_XZ)
		val cy = Math.floorDiv(y, CELL_Y)
		val cz = Math.floorDiv(z, CELL_XZ)
		val tx = (x - cx * CELL_XZ) / CELL_XZ.toDouble()
		val ty = (y - cy * CELL_Y) / CELL_Y.toDouble()
		val tz = (z - cz * CELL_XZ) / CELL_XZ.toDouble()
		val c000 = corner(field, cx, cy, cz)
		val c100 = corner(field, cx + 1, cy, cz)
		val c010 = corner(field, cx, cy + 1, cz)
		val c110 = corner(field, cx + 1, cy + 1, cz)
		val c001 = corner(field, cx, cy, cz + 1)
		val c101 = corner(field, cx + 1, cy, cz + 1)
		val c011 = corner(field, cx, cy + 1, cz + 1)
		val c111 = corner(field, cx + 1, cy + 1, cz + 1)
		val x00 = c000 + (c100 - c000) * tx
		val x10 = c010 + (c110 - c010) * tx
		val x01 = c001 + (c101 - c001) * tx
		val x11 = c011 + (c111 - c011) * tx
		val y0 = x00 + (x10 - x00) * ty
		val y1 = x01 + (x11 - x01) * ty
		return y0 + (y1 - y0) * tz
	}

	private fun corner(field: SiteField, cx: Int, cy: Int, cz: Int): Double {
		val cache = CORNERS.get()
		if (cache.field !== field) {
			cache.reset(field)
		}
		val key = (cx.toLong() shl 38) xor ((cy.toLong() and 0xFFF) shl 26) xor (cz.toLong() and 0x3FFFFFF)
		val slot = (SiteField.mix(key) and (CORNER_CACHE - 1).toLong()).toInt()
		if (cache.keys[slot] == key && cache.filled[slot]) {
			return cache.values[slot]
		}
		val v = transformed(field, cx * CELL_XZ, cy * CELL_Y, cz * CELL_XZ)
		cache.keys[slot] = key
		cache.values[slot] = v
		cache.filled[slot] = true
		return v
	}

	/** The final density of the moved terrain at a corner, with the flat surface profile of plateaus and basins. */
	private fun transformed(field: SiteField, x: Int, y: Int, z: Int): Double {
		val col = field.column(x, z, depth)
		if (!col.active) {
			return argument.compute(DensityFunction.SinglePointContext(x, y, z))
		}
		var v = argument.compute(DensityFunction.SinglePointContext(x, col.mapY(y), z))
		if (col.flat > 0) {
			// Solid down to a few blocks under the new surface, air above it; deeper down the moved terrain (caves) stays.
			val below = col.sEff - y
			val near = if (below <= 6) 1.0 else if (below >= 14) 0.0 else (14 - below) / 8.0
			if (near > 0) {
				val profile = (below * 0.2).coerceIn(-1.0, 1.0)
				v += (profile - v) * col.flat * near
			}
		}
		return v
	}

	override fun fillArray(array: DoubleArray, provider: DensityFunction.ContextProvider) {
		provider.fillAllDirectly(array, this)
	}

	override fun rebuild(
		function: (DensityFunction) -> DensityFunction,
		noise: (DensityFunction.NoiseHolder) -> DensityFunction.NoiseHolder,
	): DensityFunction = SiteDensity(mode, function(argument), function(depth), noise(this.noise), function(temperature), function(vegetation),
		function(continents), function(erosion), scale)

	override fun minValue(): Double = when (mode) {
		Mode.SURFACE -> argument.minValue() - 512
		Mode.FINAL -> min(argument.minValue(), -1.0)
		else -> argument.minValue()
	}

	override fun maxValue(): Double = when (mode) {
		Mode.SURFACE -> argument.maxValue() + 512
		Mode.FINAL -> max(argument.maxValue(), 1.0)
		else -> argument.maxValue()
	}

	override fun codec(): KeyDispatchDataCodec<out DensityFunction> = CODEC

	override fun equals(other: Any?): Boolean = other is SiteDensity && mode == other.mode && argument == other.argument &&
		depth == other.depth && noise == other.noise && temperature == other.temperature && vegetation == other.vegetation &&
		continents == other.continents && erosion == other.erosion && scale == other.scale

	override fun hashCode(): Int = Objects.hash(mode, argument, depth, noise, temperature, vegetation, continents, erosion, scale)

	private class CornerCache {
		var field: SiteField? = null
		val keys = LongArray(CORNER_CACHE)
		val values = DoubleArray(CORNER_CACHE)
		val filled = BooleanArray(CORNER_CACHE)

		fun reset(f: SiteField) {
			field = f
			filled.fill(false)
		}
	}

	companion object {
		private const val CELL_XZ = 4
		private const val CELL_Y = 8
		private const val CORNER_CACHE = 1 shl 14
		private val CORNERS: ThreadLocal<CornerCache> = ThreadLocal.withInitial { CornerCache() }

		private val MODE_CODEC: Codec<Mode> = Codec.STRING.xmap({ Mode.valueOf(it.uppercase()) }, { it.name.lowercase() })

		@JvmField
		val DATA_CODEC: MapCodec<SiteDensity> = RecordCodecBuilder.mapCodec { i ->
			i.group(
				MODE_CODEC.fieldOf("mode").forGetter(SiteDensity::mode),
				DensityCompat.FUNCTION.fieldOf("argument").forGetter(SiteDensity::argument),
				DensityCompat.FUNCTION.fieldOf("depth").forGetter(SiteDensity::depth),
				DensityFunction.NoiseHolder.CODEC.fieldOf("noise").forGetter(SiteDensity::noise),
				DensityCompat.FUNCTION.fieldOf("temperature").forGetter(SiteDensity::temperature),
				DensityCompat.FUNCTION.fieldOf("vegetation").forGetter(SiteDensity::vegetation),
				DensityCompat.FUNCTION.fieldOf("continents").forGetter(SiteDensity::continents),
				DensityCompat.FUNCTION.fieldOf("erosion").forGetter(SiteDensity::erosion),
				Codec.DOUBLE.optionalFieldOf("scale", 1.0).forGetter(SiteDensity::scale),
			).apply(i) { mode, argument, depth, noise, temperature, vegetation, continents, erosion, scale ->
				SiteDensity(mode, argument, depth, noise, temperature, vegetation, continents, erosion, scale)
			}
		}

		@JvmField
		val CODEC: KeyDispatchDataCodec<SiteDensity> = KeyDispatchDataCodec.of(DATA_CODEC)

		/** Vanilla terrain offset whose surface sits at block height y (depth = 1.5 - (y + 64) / 128 + offset = 0). */
		fun offsetForY(y: Double): Double = (y + 64) / 128.0 - 1.5

		internal fun floorInt(v: Double): Int = floor(v).toInt()
	}
}
