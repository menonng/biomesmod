package io.github.menonng.biomesmod.compat;

import net.minecraft.world.level.LevelHeightAccessor;

/** Version shim: lowest buildable Y is LevelHeightAccessor#getMinY() in 26.2. */
public final class LevelCompat {
	private LevelCompat() {
	}

	public static int minY(LevelHeightAccessor level) {
		return level.getMinY();
	}
}
