package io.github.menonng.biomesmod;

import io.github.menonng.biomesmod.terrain.ModFeatures;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BiomesMod implements ModInitializer {
	public static final String MOD_ID = "biomesmod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModFeatures.register();
		LOGGER.info("Loaded {} biome(s)", ModBiomes.ALL.size());

		if (Boolean.getBoolean("biomesmod.selfcheck")) {
			ServerLifecycleEvents.SERVER_STARTED.register(SelfCheck::run);
		}
	}
}
