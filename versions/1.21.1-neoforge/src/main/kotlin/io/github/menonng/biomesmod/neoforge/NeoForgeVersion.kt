package io.github.menonng.biomesmod.neoforge

import io.github.menonng.biomesmod.ModBlocks
import net.minecraft.client.renderer.ItemBlockRenderTypes
import net.minecraft.client.renderer.RenderType
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.fml.loading.FMLEnvironment

/** The NeoForge calls that differ between Minecraft versions (1.21.1). */
object NeoForgeVersion {
	fun clientSetup(bus: IEventBus) {
		if (FMLEnvironment.dist.isClient) bus.addListener(FMLClientSetupEvent::class.java) { NeoForgeClient.cutout() }
	}
}

/** Client only: cut-out render layers (plants, crystals) and mipped cut-out (leaves). */
internal object NeoForgeClient {
	@Suppress("DEPRECATION")
	fun cutout() {
		ModBlocks.CUTOUT.forEach { ItemBlockRenderTypes.setRenderLayer(it, RenderType.cutout()) }
		ModBlocks.CUTOUT_MIPPED.forEach { ItemBlockRenderTypes.setRenderLayer(it, RenderType.cutoutMipped()) }
	}
}
