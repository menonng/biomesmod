package io.github.menonng.biomesmod.terrain.landform

import io.github.menonng.biomesmod.compat.LandformCompat
import io.github.menonng.biomesmod.terrain.util.StrataPalette
import net.minecraft.world.level.block.state.BlockState
import java.util.Optional

/** Block helpers for landform defaults (ids, not Java fields, so dyed blocks work in both versions). */
internal object B {
	fun of(id: String): BlockState = LandformCompat.block(if (id.indexOf(':') < 0) "minecraft:$id" else id)

	fun list(vararg ids: String): List<BlockState> = ids.map { of(it) }

	/** Strata palette from alternating (id, thickness) pairs, optional top. */
	fun strata(top: String?, topDepth: Int, vararg pairs: Any): StrataPalette {
		val layers = (pairs.indices step 2).map { StrataPalette.Layer(of(pairs[it] as String), pairs[it + 1] as Int) }
		return StrataPalette(if (top == null) Optional.empty() else Optional.of(of(top)), topDepth, layers, 0)
	}
}
