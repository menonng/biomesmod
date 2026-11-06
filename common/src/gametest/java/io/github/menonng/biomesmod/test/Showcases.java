package io.github.menonng.biomesmod.test;

import io.github.menonng.biomesmod.BiomesMod;
import io.github.menonng.biomesmod.test.showcase.CoastShowcase;
import io.github.menonng.biomesmod.test.showcase.FieldShowcase;
import io.github.menonng.biomesmod.test.showcase.LandformShowcase;
import java.util.function.Consumer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

/**
 * Dev-only engine showcase runner (gametest source set, never shipped).
 * {@code ./gradlew :<mc>:runSelfCheck -Pshowcase=field|landform|coast} places that engine's features
 * on a normal world, renders PNGs into run/selfcheck/renders/, then exits (SelfCheck is skipped).
 * Each showcase class is owned by the matching engine agent.
 */
public final class Showcases implements ModInitializer {
	@Override
	public void onInitialize() {
		String which = System.getProperty("biomesmod.showcase", "");
		Consumer<MinecraftServer> showcase = switch (which) {
			case "field" -> FieldShowcase::run;
			case "landform" -> LandformShowcase::run;
			case "coast" -> CoastShowcase::run;
			default -> null;
		};
		if (showcase == null) {
			return;
		}
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			int code = 0;
			try {
				showcase.accept(server);
				BiomesMod.LOGGER.info("[showcase] {} done", which);
			} catch (Throwable t) {
				BiomesMod.LOGGER.error("[showcase] {} failed", which, t);
				code = 1;
			}
			int exit = code;
			new Thread(() -> System.exit(exit), "biomesmod-showcase-exit").start();
		});
	}
}
