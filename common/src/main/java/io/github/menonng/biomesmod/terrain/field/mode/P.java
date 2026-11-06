package io.github.menonng.biomesmod.terrain.field.mode;

import io.github.menonng.biomesmod.compat.LandformCompat;
import io.github.menonng.biomesmod.terrain.field.FieldPalette;
import io.github.menonng.biomesmod.terrain.util.StrataPalette;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Small builders for mode default palettes (vanilla blocks only, so they exist in every version). */
final class P {
	private P() {
	}

	static BlockState s(Object b) {
		return b instanceof Block block ? block.defaultBlockState() : id((String) b);
	}

	/** Block by id: needed for dyed blocks, which are separate fields in 1.21.1 but ColorCollections in 26.2. */
	static BlockState id(String id) {
		return LandformCompat.block(id.indexOf(':') < 0 ? "minecraft:" + id : id);
	}

	/** layers(block, depth, block, depth, ...) */
	static List<FieldPalette.Layer> layers(Object... blockDepth) {
		List<FieldPalette.Layer> out = new ArrayList<>();
		for (int k = 0; k < blockDepth.length; k += 2) {
			out.add(new FieldPalette.Layer(s(blockDepth[k]), (Integer) blockDepth[k + 1]));
		}
		return out;
	}

	/** strata(block, thickness, block, thickness, ...) */
	static StrataPalette strata(Object... blockThickness) {
		List<StrataPalette.Layer> out = new ArrayList<>();
		for (int k = 0; k < blockThickness.length; k += 2) {
			out.add(new StrataPalette.Layer(s(blockThickness[k]), (Integer) blockThickness[k + 1]));
		}
		return new StrataPalette(Optional.empty(), 1, out, 0);
	}

	static FieldPalette palette(List<FieldPalette.Layer> top, List<FieldPalette.Layer> underwater, StrataPalette strata, int cliffSlope) {
		return new FieldPalette(Optional.ofNullable(top), Optional.ofNullable(underwater), Optional.ofNullable(strata), List.of(),
				Optional.of(cliffSlope), Optional.empty());
	}
}
