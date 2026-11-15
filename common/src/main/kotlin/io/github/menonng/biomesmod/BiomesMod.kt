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
		// The beach of the Regon data pack on every beach biome: a flat strand at sea level and an even slope into the sea.
		net.fabricmc.fabric.api.biome.v1.BiomeModifications.addFeature(
			// Not on this mod's own biomes: they shape their own shore, and the flats would level it again.
			net.fabricmc.fabric.api.biome.v1.BiomeSelectors.tag(net.minecraft.tags.BiomeTags.IS_BEACH)
				.and { !io.github.menonng.biomesmod.compat.Ids.name(it.biomeKey).startsWith(MOD_ID + ":") },
			net.minecraft.world.level.levelgen.GenerationStep.Decoration.RAW_GENERATION,
			net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.PLACED_FEATURE, io.github.menonng.biomesmod.compat.Ids.id("coast_flats")))
		ServerLifecycleEvents.SERVER_STARTING.register { NoFreeze.load(it) }
		LOGGER.info("Loaded {} biome(s)", ModBiomes.ALL.size)

		val maps = System.getProperty("biomesmod.maps", "")
		if (maps.isNotBlank()) {
			// Docs map mode: generate and render every map of the plan, then stop (the world stays for the screenshots).
			ServerLifecycleEvents.SERVER_STARTED.register { server ->
				DebugRender.mapPlan(server, maps) {
					if (java.lang.Boolean.getBoolean("biomesmod.selfcheck")) {
						server.saveEverything(false, true, true)
						Thread({ kotlin.system.exitProcess(0) }, "biomesmod-maps-exit").start()
					}
				}
			}
		} else if (java.lang.Boolean.getBoolean("biomesmod.selfcheck")) {
			ServerLifecycleEvents.SERVER_STARTED.register { SelfCheck.run(it) }
		}
	}

	companion object {
		const val MOD_ID = "biomesmod"

		@JvmField
		val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)
	}
}
