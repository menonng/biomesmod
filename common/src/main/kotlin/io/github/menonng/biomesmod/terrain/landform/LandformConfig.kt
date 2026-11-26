package io.github.menonng.biomesmod.terrain.landform

import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.menonng.biomesmod.terrain.util.StrataPalette
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Config of `biomesmod:landform`: grid placement + biome gating + materials, plus the shape's own parameters at the
 * same JSON level (dispatched on `"shape"`). See docs/engine-landform.md.
 */
class LandformConfig(
	val shape: LandformShape,
	internal val spacing: Int,
	internal val chance: Float,
	internal val jitter: Float,
	internal val salt: Int,
	internal val biomes: BiomeFilter,
	internal val requireInside: Boolean,
	private val palette: Optional<StrataPalette>,
	private val talusBlocks: List<BlockState>,
	internal val surfaceBlocks: List<BlockState>,
	private val cap: Optional<Cap>,
	private val baseYRange: Optional<Span>,
	private val maxRelief: Optional<Int>,
	private val riseFrom: String,
	/** Minimum centre distance between two instances; -1 = one per site (unique landmarks: Devils Tower, Alaid). */
	internal val minDistance: Int,
) : FeatureConfiguration {
	/** Resolved instances per (generator, seed, cell). Never shared between configs. */
	internal val cache = ConcurrentHashMap<CellKey, Any>()
	internal val rawCache = ConcurrentHashMap<CellKey, Any>()
	internal val superCache = ConcurrentHashMap<CellKey, Any>()

	internal class CellKey(val generator: Any, val seed: Long, val cx: Int, val cz: Int) {
		override fun equals(other: Any?): Boolean =
			other is CellKey && other.generator === generator && other.seed == seed && other.cx == cx && other.cz == cz

		override fun hashCode(): Int = System.identityHashCode(generator) * 31 + seed.hashCode() * 17 + cx * 92821 + cz
	}

	internal fun palette(): StrataPalette = palette.orElseGet { shape.defaultPalette() }

	internal fun talus(): List<BlockState> = talusBlocks.ifEmpty { shape.defaultTalus() }

	internal fun cap(): Optional<Cap> = if (cap.isPresent) cap else shape.defaultCap()

	internal fun baseYRange(): Span = baseYRange.orElseGet { shape.defaultBaseRange() }

	internal fun maxRelief(): Int = maxRelief.orElseGet { shape.defaultMaxRelief() }

	internal fun riseFromSea(): Boolean = riseFrom == "sea_level"

	companion object {
		private val RISE: Codec<String> = Codec.STRING.flatXmap({ s ->
			if (s == "ground" || s == "sea_level") DataResult.success(s) else DataResult.error { "rise_from must be ground or sea_level: $s" }
		}, { DataResult.success(it) })

		@JvmField
		val CODEC: Codec<LandformConfig> = RecordCodecBuilder.create { i ->
			i.group(
				LandformShapes.MAP_CODEC.forGetter { it.shape },
				Codec.intRange(8, 4096).optionalFieldOf("spacing", 128).forGetter { it.spacing },
				Codec.floatRange(0f, 1f).optionalFieldOf("chance", 0.5f).forGetter { it.chance },
				Codec.floatRange(0f, 1f).optionalFieldOf("jitter", 0.75f).forGetter { it.jitter },
				Codec.INT.optionalFieldOf("salt", 0).forGetter { it.salt },
				BiomeFilter.CODEC.optionalFieldOf("biomes", BiomeFilter.ANY).forGetter { it.biomes },
				Codec.BOOL.optionalFieldOf("require_inside", true).forGetter { it.requireInside },
				StrataPalette.CODEC.optionalFieldOf("palette").forGetter { it.palette },
				BlockState.CODEC.listOf().optionalFieldOf("talus_blocks", listOf()).forGetter { it.talusBlocks },
				BlockState.CODEC.listOf().optionalFieldOf("surface_blocks", listOf()).forGetter { it.surfaceBlocks },
				Cap.CODEC.optionalFieldOf("cap").forGetter { it.cap },
				Span.CODEC.optionalFieldOf("base_y_range").forGetter { it.baseYRange },
				Codec.intRange(0, 512).optionalFieldOf("max_relief").forGetter { it.maxRelief },
				RISE.optionalFieldOf("rise_from", "ground").forGetter { it.riseFrom },
				Codec.intRange(-1, 4096).optionalFieldOf("min_distance", 0).forGetter { it.minDistance },
			).apply(i) { shape, spacing, chance, jitter, salt, biomes, requireInside, palette, talusBlocks, surfaceBlocks, cap, baseYRange,
				maxRelief, riseFrom, minDistance ->
				LandformConfig(shape, spacing, chance, jitter, salt, biomes, requireInside, palette, talusBlocks, surfaceBlocks, cap, baseYRange,
					maxRelief, riseFrom, minDistance)
			}
		}

		/** Builds a config in code (showcase/tests) from a shape with all other settings at their defaults. */
		fun of(shape: LandformShape, spacing: Int, chance: Float, salt: Int): LandformConfig =
			LandformConfig(shape, spacing, chance, 0.75f, salt, BiomeFilter.ANY, true, Optional.empty(), listOf(), listOf(), Optional.empty(),
				Optional.empty(), Optional.empty(), "ground", 0)
	}
}
