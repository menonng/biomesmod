package io.github.menonng.biomesmod.terrain.landform;

import io.github.menonng.biomesmod.compat.LandformCompat;
import io.github.menonng.biomesmod.terrain.util.StrataPalette;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.level.block.state.BlockState;

/** Block helpers for landform defaults (ids, not Java fields, so dyed blocks work in both versions). */
final class B {
	private B() {
	}

	static BlockState of(String id) {
		return LandformCompat.block(id.indexOf(':') < 0 ? "minecraft:" + id : id);
	}

	static List<BlockState> list(String... ids) {
		List<BlockState> out = new ArrayList<>(ids.length);
		for (String id : ids) {
			out.add(of(id));
		}
		return List.copyOf(out);
	}

	/** Strata palette from alternating (id, thickness) pairs, optional top. */
	static StrataPalette strata(String top, int topDepth, Object... pairs) {
		List<StrataPalette.Layer> layers = new ArrayList<>();
		for (int i = 0; i < pairs.length; i += 2) {
			layers.add(new StrataPalette.Layer(of((String) pairs[i]), (Integer) pairs[i + 1]));
		}
		return new StrataPalette(top == null ? Optional.empty() : Optional.of(of(top)), topDepth, List.copyOf(layers), 0);
	}
}
