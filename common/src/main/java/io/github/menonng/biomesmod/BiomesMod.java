package io.github.menonng.biomesmod;

import io.github.menonng.biomesmod.compat.Ids;
import io.github.menonng.biomesmod.site.SiteCommand;
import io.github.menonng.biomesmod.site.SiteDensity;
import io.github.menonng.biomesmod.terrain.ModFeatures;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BiomesMod implements ModInitializer {
	public static final String MOD_ID = "biomesmod";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ModBlocks.register();
		ModFeatures.register();
		Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, Ids.id("site"), SiteDensity.DATA_CODEC);
		SiteCommand.register();
		LOGGER.info("Loaded {} biome(s)", ModBiomes.ALL.size());

		if (Boolean.getBoolean("biomesmod.selfcheck")) {
			ServerLifecycleEvents.SERVER_STARTED.register(SelfCheck::run);
		}
	}
}
