package io.github.menonng.biomesmod.test

import io.github.menonng.biomesmod.BiomesMod
import io.github.menonng.biomesmod.test.showcase.CoastShowcase
import io.github.menonng.biomesmod.test.showcase.FieldShowcase
import io.github.menonng.biomesmod.test.showcase.LandformShowcase
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.server.MinecraftServer
import kotlin.system.exitProcess

/**
 * Dev-only engine showcase runner (gametest source set, never shipped). `./gradlew :<mc>:runSelfCheck
 * -Pshowcase=field|landform|coast` places that engine's features on a normal world, renders PNGs into
 * run/selfcheck/renders/, then exits (SelfCheck is skipped).
 */
class Showcases : ModInitializer {
	override fun onInitialize() {
		val which = System.getProperty("biomesmod.showcase", "")
		val showcase: ((MinecraftServer) -> Unit) = when (which) {
			"field" -> FieldShowcase::run
			"landform" -> LandformShowcase::run
			"coast" -> CoastShowcase::run
			else -> return
		}
		ServerLifecycleEvents.SERVER_STARTED.register { server ->
			var code = 0
			try {
				showcase(server)
				BiomesMod.LOGGER.info("[showcase] {} done", which)
			} catch (t: Throwable) {
				BiomesMod.LOGGER.error("[showcase] {} failed", which, t)
				code = 1
			}
			val exit = code
			Thread({ exitProcess(exit) }, "biomesmod-showcase-exit").start()
		}
	}
}
