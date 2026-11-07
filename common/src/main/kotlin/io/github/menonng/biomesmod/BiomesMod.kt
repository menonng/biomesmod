package io.github.menonng.biomesmod

import io.github.menonng.biomesmod.compat.Ids
import io.github.menonng.biomesmod.debug.DebugRender
import io.github.menonng.biomesmod.site.SiteCommand
import io.github.menonng.biomesmod.site.SiteDensity
import io.github.menonng.biomesmod.terrain.ModFeatures
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class BiomesMod : ModInitializer {
	override fun onInitialize() {
		ModBlocks.register()
		ModFeatures.register()
		Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, Ids.id("site"), SiteDensity.DATA_CODEC)
		SiteCommand.register()
		LOGGER.info("Loaded {} biome(s)", ModBiomes.ALL.size)

		if (java.lang.Boolean.getBoolean("biomesmod.selfcheck")) {
			ServerLifecycleEvents.SERVER_STARTED.register { SelfCheck.run(it) }
		}
		System.getProperty("biomesmod.maps")?.let { plan ->
			ServerLifecycleEvents.SERVER_STARTED.register { DebugRender.mapPlan(it, plan) }
		}
	}

	companion object {
		const val MOD_ID = "biomesmod"

		@JvmField
		val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)
	}
}
