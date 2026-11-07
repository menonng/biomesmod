package io.github.menonng.biomesmod.terrain.field

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import io.github.menonng.biomesmod.terrain.util.StrataPalette
import net.minecraft.world.level.block.state.BlockState
import java.util.Optional

/**
 * Blocks the terrain shaper dresses reshaped columns with.
 *
 * ```
 * "palette": {
 *   "top":        [{"block": {"Name": "minecraft:sand"}, "depth": 3}, {"block": {"Name": "minecraft:sandstone"}, "depth": 2}],
 *   "underwater": [{"block": {"Name": "minecraft:mud"}, "depth": 2}],   // columns with water directly above
 *   "strata":     { StrataPalette },                                     // fill below the top layers and cliff faces
 *   "patches":    [{"block": {...}, "coverage": 0.2, "scale": 24}],     // noise patches on the surface block
 *   "cliff_slope": 3,                                                    // drop to the lowest neighbour that makes a cliff
 *   "fluid":      {"Name": "minecraft:water"}
 * }
 * ```
 *
 * Every field is optional. Missing fields fall back to the mode's defaults, then to "auto": the column's own original
 * surface blocks (so a lowered meadow stays grassy) and its original deep block (stone, sandstone...).
 */
data class FieldPalette(
	val top: Optional<List<Layer>>,
	val underwater: Optional<List<Layer>>,
	val strata: Optional<StrataPalette>,
	val patches: List<Patch>,
	val cliffSlope: Optional<Int>,
	val fluid: Optional<BlockState>,
) {
	data class Layer(val block: BlockState, val depth: Int) {
		companion object {
			@JvmField
			val CODEC: Codec<Layer> = RecordCodecBuilder.create { i ->
				i.group(
					BlockState.CODEC.fieldOf("block").forGetter(Layer::block),
					Codec.intRange(1, 64).optionalFieldOf("depth", 1).forGetter(Layer::depth),
				).apply(i) { block, depth -> Layer(block, depth) }
			}
		}
	}

	/** A noise patch of another surface block; coverage is the fraction of the surface it takes. */
	data class Patch(val block: BlockState, val coverage: Double, val scale: Double, val underwater: Boolean) {
		companion object {
			@JvmField
			val CODEC: Codec<Patch> = RecordCodecBuilder.create { i ->
				i.group(
					BlockState.CODEC.fieldOf("block").forGetter(Patch::block),
					Codec.doubleRange(0.0, 1.0).optionalFieldOf("coverage", 0.2).forGetter(Patch::coverage),
					Codec.doubleRange(1.0, 4096.0).optionalFieldOf("scale", 24.0).forGetter(Patch::scale),
					Codec.BOOL.optionalFieldOf("underwater", false).forGetter(Patch::underwater),
				).apply(i) { block, coverage, scale, underwater -> Patch(block, coverage, scale, underwater) }
			}
		}
	}

	/** Field-by-field overlay: values set in [over] win, the rest come from this palette. */
	fun overlay(over: FieldPalette): FieldPalette = FieldPalette(
		if (over.top.isPresent) over.top else top,
		if (over.underwater.isPresent) over.underwater else underwater,
		if (over.strata.isPresent) over.strata else strata,
		over.patches.ifEmpty { patches },
		if (over.cliffSlope.isPresent) over.cliffSlope else cliffSlope,
		if (over.fluid.isPresent) over.fluid else fluid)

	companion object {
		@JvmField
		val CODEC: Codec<FieldPalette> = RecordCodecBuilder.create { i ->
			i.group(
				Layer.CODEC.listOf().optionalFieldOf("top").forGetter(FieldPalette::top),
				Layer.CODEC.listOf().optionalFieldOf("underwater").forGetter(FieldPalette::underwater),
				StrataPalette.CODEC.optionalFieldOf("strata").forGetter(FieldPalette::strata),
				Patch.CODEC.listOf().optionalFieldOf("patches", listOf()).forGetter(FieldPalette::patches),
				Codec.intRange(1, 1024).optionalFieldOf("cliff_slope").forGetter(FieldPalette::cliffSlope),
				BlockState.CODEC.optionalFieldOf("fluid").forGetter(FieldPalette::fluid),
			).apply(i) { top, underwater, strata, patches, cliffSlope, fluid -> FieldPalette(top, underwater, strata, patches, cliffSlope, fluid) }
		}

		@JvmField
		val EMPTY = FieldPalette(Optional.empty(), Optional.empty(), Optional.empty(), listOf(), Optional.empty(), Optional.empty())

		fun of(top: List<Layer>?, strata: StrataPalette?, cliffSlope: Int): FieldPalette =
			FieldPalette(Optional.ofNullable(top), Optional.empty(), Optional.ofNullable(strata), listOf(), Optional.of(cliffSlope), Optional.empty())
	}
}
