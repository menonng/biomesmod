package io.github.menonng.biomesmod.fabric

import io.github.menonng.biomesmod.BiomesMod
import io.github.menonng.biomesmod.compat.Ids
import io.github.menonng.biomesmod.platform.Loader
import io.github.menonng.biomesmod.platform.LoaderHooks
import io.github.menonng.biomesmod.site.SiteCommand
import io.github.menonng.biomesmod.worldgen.TerraBlenderEntry
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.biome.v1.BiomeModifications
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.registry.FlammableBlockRegistry
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.tags.BiomeTags
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.ComposterBlock
import net.minecraft.world.level.levelgen.GenerationStep
import terrablender.api.TerraBlenderApi
import java.nio.file.Path

object FabricHooks : LoaderHooks {
	override val configDir: Path get() = FabricLoader.getInstance().configDir
	override fun naturalTab(items: List<Item>) = FabricVersion.naturalTab(items)
	override fun buildingTab(items: List<Item>) = FabricVersion.buildingTab(items)
	override fun flammable(block: Block, burn: Int, spread: Int) = FlammableBlockRegistry.getDefaultInstance().add(block, burn, spread)
	override fun compostable(block: Block, chance: Float) {
		ComposterBlock.COMPOSTABLES.put(block, chance)
	}
}

class FabricEntry : ModInitializer {
	override fun onInitialize() {
		Loader.hooks = FabricHooks
		BiomesMod.register()
		CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> SiteCommand.register(dispatcher) }
		// The beach of the Regon data pack on every beach biome: a flat strand at sea level and an even slope into the sea.
		// Not on this mod's own biomes: they shape their own shore, and the flats would level it again.
		BiomeModifications.addFeature(
			BiomeSelectors.tag(BiomeTags.IS_BEACH).and { !Ids.name(it.biomeKey).startsWith(BiomesMod.MOD_ID + ":") },
			GenerationStep.Decoration.RAW_GENERATION,
			ResourceKey.create(Registries.PLACED_FEATURE, Ids.id("coast_flats")))
		ServerLifecycleEvents.SERVER_STARTING.register { BiomesMod.serverStarting(it) }
		ServerLifecycleEvents.SERVER_STARTED.register { BiomesMod.serverStarted(it) }
	}
}

/** Client setup: render layers of the cut-out blocks (leaves, plants, crystals). */
class FabricClientEntry : ClientModInitializer {
	override fun onInitializeClient() {
		FabricVersion.cutout(io.github.menonng.biomesmod.ModBlocks.CUTOUT, io.github.menonng.biomesmod.ModBlocks.CUTOUT_MIPPED)
	}
}

/** The "terrablender" entrypoint. TerraBlender may call it before the main entry point (it does on 1.20.1). */
class FabricTerraBlender : TerraBlenderApi {
	override fun onTerraBlenderInitialized() {
		Loader.hooks = FabricHooks
		TerraBlenderEntry.init()
	}
}
