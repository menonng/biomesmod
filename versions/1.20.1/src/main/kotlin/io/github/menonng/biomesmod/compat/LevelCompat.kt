package io.github.menonng.biomesmod.compat

import net.minecraft.world.level.LevelHeightAccessor

/** Version shim: lowest buildable Y (getMinBuildHeight() in 1.21.1). */
object LevelCompat {
	fun minY(level: LevelHeightAccessor): Int = level.minBuildHeight
}
