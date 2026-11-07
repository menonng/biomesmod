package io.github.menonng.biomesmod.compat

import net.minecraft.world.level.LevelHeightAccessor

/** Version shim: lowest buildable Y (getMinY() in 26.2). */
object LevelCompat {
	fun minY(level: LevelHeightAccessor): Int = level.minY
}
