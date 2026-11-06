package io.github.menonng.biomesmod.terrain.field;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.menonng.biomesmod.terrain.util.StrataPalette;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Blocks the terrain shaper dresses reshaped columns with.
 *
 * <pre>
 * "palette": {
 *   "top":        [{"block": {"Name": "minecraft:sand"}, "depth": 3}, {"block": {"Name": "minecraft:sandstone"}, "depth": 2}],
 *   "underwater": [{"block": {"Name": "minecraft:mud"}, "depth": 2}],   // columns with water directly above
 *   "strata":     { StrataPalette },                                     // fill below the top layers and cliff faces
 *   "patches":    [{"block": {...}, "coverage": 0.2, "scale": 24}],     // noise patches on the surface block
 *   "cliff_slope": 3,                                                    // drop to the lowest neighbour that makes a cliff
 *   "fluid":      {"Name": "minecraft:water"}
 * }
 * </pre>
 *
 * Every field is optional. Missing fields fall back to the mode's defaults, then to "auto": the column's own
 * original surface blocks (so a lowered meadow stays grassy) and its original deep block (stone, sandstone...).
 */
public record FieldPalette(Optional<List<Layer>> top, Optional<List<Layer>> underwater, Optional<StrataPalette> strata,
		List<Patch> patches, Optional<Integer> cliffSlope, Optional<BlockState> fluid) {

	public record Layer(BlockState block, int depth) {
		public static final Codec<Layer> CODEC = RecordCodecBuilder.create(i -> i.group(
				BlockState.CODEC.fieldOf("block").forGetter(Layer::block),
				Codec.intRange(1, 64).optionalFieldOf("depth", 1).forGetter(Layer::depth)
		).apply(i, Layer::new));
	}

	/** A noise patch of another surface block; coverage is the fraction of the surface it takes. */
	public record Patch(BlockState block, double coverage, double scale, boolean underwater) {
		public static final Codec<Patch> CODEC = RecordCodecBuilder.create(i -> i.group(
				BlockState.CODEC.fieldOf("block").forGetter(Patch::block),
				Codec.doubleRange(0, 1).optionalFieldOf("coverage", 0.2).forGetter(Patch::coverage),
				Codec.doubleRange(1, 4096).optionalFieldOf("scale", 24.0).forGetter(Patch::scale),
				Codec.BOOL.optionalFieldOf("underwater", false).forGetter(Patch::underwater)
		).apply(i, Patch::new));
	}

	public static final Codec<FieldPalette> CODEC = RecordCodecBuilder.create(i -> i.group(
			Layer.CODEC.listOf().optionalFieldOf("top").forGetter(FieldPalette::top),
			Layer.CODEC.listOf().optionalFieldOf("underwater").forGetter(FieldPalette::underwater),
			StrataPalette.CODEC.optionalFieldOf("strata").forGetter(FieldPalette::strata),
			Patch.CODEC.listOf().optionalFieldOf("patches", List.of()).forGetter(FieldPalette::patches),
			Codec.intRange(1, 1024).optionalFieldOf("cliff_slope").forGetter(FieldPalette::cliffSlope),
			BlockState.CODEC.optionalFieldOf("fluid").forGetter(FieldPalette::fluid)
	).apply(i, FieldPalette::new));

	public static final FieldPalette EMPTY = new FieldPalette(Optional.empty(), Optional.empty(), Optional.empty(), List.of(),
			Optional.empty(), Optional.empty());

	/** Field-by-field overlay: values set in {@code over} win, the rest come from this palette. */
	public FieldPalette overlay(FieldPalette over) {
		return new FieldPalette(
				over.top.isPresent() ? over.top : top,
				over.underwater.isPresent() ? over.underwater : underwater,
				over.strata.isPresent() ? over.strata : strata,
				!over.patches.isEmpty() ? over.patches : patches,
				over.cliffSlope.isPresent() ? over.cliffSlope : cliffSlope,
				over.fluid.isPresent() ? over.fluid : fluid);
	}

	public static FieldPalette of(List<Layer> top, StrataPalette strata, int cliffSlope) {
		return new FieldPalette(Optional.ofNullable(top), Optional.empty(), Optional.ofNullable(strata), List.of(),
				Optional.of(cliffSlope), Optional.empty());
	}
}
