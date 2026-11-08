package io.github.menonng.biomesmod

import io.github.menonng.biomesmod.compat.BlockCompat
import net.fabricmc.api.ClientModInitializer

/** Client setup: render layers of the cut-out blocks (leaves, plants, crystals). */
class BiomesModClient : ClientModInitializer {
	override fun onInitializeClient() {
		BlockCompat.cutout(ModBlocks.CUTOUT, ModBlocks.CUTOUT_MIPPED)
	}
}
