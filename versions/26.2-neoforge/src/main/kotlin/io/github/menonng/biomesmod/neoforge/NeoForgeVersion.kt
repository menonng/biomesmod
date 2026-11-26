package io.github.menonng.biomesmod.neoforge

import net.neoforged.bus.api.IEventBus

/** The NeoForge calls that differ between Minecraft versions (26.2: render layers come from the textures, nothing to set). */
object NeoForgeVersion {
	fun clientSetup(bus: IEventBus) {}
}
