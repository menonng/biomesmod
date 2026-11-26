package io.github.menonng.biomesmod

import io.github.menonng.biomesmod.compat.Ids
import io.github.menonng.biomesmod.debug.DebugRender
import io.github.menonng.biomesmod.site.SiteDensity
import io.github.menonng.biomesmod.terrain.ModFeatures
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * The loader-independent half of the mod. Each loader's entry point sets [io.github.menonng.biomesmod.platform.Loader.hooks],
 * calls [register] while the registries are open, and forwards the server lifecycle to [serverStarting] and [serverStarted].
 */
object BiomesMod {
	const val MOD_ID = "biomesmod"

	@JvmField
	val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)

	/** Blocks, items, features and the site density function. */
	fun register() {
		ModBlocks.register()
		ModFeatures.register()
		io.github.menonng.biomesmod.compat.CodecCompat.registerDensityType(Ids.id("site"), SiteDensity.DATA_CODEC)
		LOGGER.info("Loaded {} biome(s)", ModBiomes.ALL.size)
	}

	fun serverStarting(server: MinecraftServer) {
		NoFreeze.load(server)
	}

	fun serverStarted(server: MinecraftServer) {
		val maps = System.getProperty("biomesmod.maps", "")
		if (maps.isNotBlank()) {
			// Docs map mode: generate and render every map of the plan, then stop (the world stays for the screenshots).
			DebugRender.mapPlan(server, maps) {
				if (java.lang.Boolean.getBoolean("biomesmod.selfcheck")) {
					server.saveEverything(false, true, true)
					Thread({ kotlin.system.exitProcess(0) }, "biomesmod-maps-exit").start()
				}
			}
		} else if (java.lang.Boolean.getBoolean("biomesmod.selfcheck")) {
			SelfCheck.run(server)
		}
	}
}
