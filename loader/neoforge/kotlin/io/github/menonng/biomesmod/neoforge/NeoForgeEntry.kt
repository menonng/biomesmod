package io.github.menonng.biomesmod.neoforge

import io.github.menonng.biomesmod.BiomesMod
import io.github.menonng.biomesmod.ModBlocks
import io.github.menonng.biomesmod.neoforge.mixin.FireBlockAccess
import io.github.menonng.biomesmod.platform.Loader
import io.github.menonng.biomesmod.platform.LoaderHooks
import io.github.menonng.biomesmod.site.SiteCommand
import io.github.menonng.biomesmod.worldgen.TerraBlenderEntry
import net.minecraft.core.registries.Registries
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RotatedPillarBlock
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.common.ItemAbilities
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.server.ServerStartedEvent
import net.neoforged.neoforge.event.server.ServerStartingEvent
import net.neoforged.neoforge.registries.RegisterEvent
import java.nio.file.Path

/** Composting comes from the neoforge:compostables data map in the jar; the rest is set up here. */
object NeoForgeHooks : LoaderHooks {
	val natural = mutableListOf<Item>()
	val building = mutableListOf<Item>()
	override val configDir: Path get() = FMLPaths.CONFIGDIR.get()
	override fun naturalTab(items: List<Item>) {
		natural += items
	}
	override fun buildingTab(items: List<Item>) {
		building += items
	}
	override fun flammable(block: Block, burn: Int, spread: Int) = (Blocks.FIRE as FireBlockAccess).biomesmod_setFlammable(block, burn, spread)
	override fun compostable(block: Block, chance: Float) {}
}

@Mod(BiomesMod.MOD_ID)
class NeoForgeEntry(modBus: IEventBus, container: ModContainer) {
	init {
		Loader.hooks = NeoForgeHooks
		// Every vanilla registry is open while the RegisterEvents fire: register everything with the first one.
		modBus.addListener(RegisterEvent::class.java) { e -> if (e.registryKey == Registries.BLOCK) BiomesMod.register() }
		modBus.addListener(FMLCommonSetupEvent::class.java) { e -> e.enqueueWork { TerraBlenderEntry.init() } }
		modBus.addListener(BuildCreativeModeTabContentsEvent::class.java) { e ->
			when (e.tabKey) {
				CreativeModeTabs.NATURAL_BLOCKS -> NeoForgeHooks.natural.forEach { e.accept(it) }
				CreativeModeTabs.BUILDING_BLOCKS -> NeoForgeHooks.building.forEach { e.accept(it) }
			}
		}
		NeoForgeVersion.clientSetup(modBus)
		NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { e -> SiteCommand.register(e.dispatcher) }
		NeoForge.EVENT_BUS.addListener(ServerStartingEvent::class.java) { e -> BiomesMod.serverStarting(e.server) }
		NeoForge.EVENT_BUS.addListener(ServerStartedEvent::class.java) { e -> BiomesMod.serverStarted(e.server) }
		// Stripping the mod's logs and woods with an axe.
		NeoForge.EVENT_BUS.addListener(BlockEvent.BlockToolModificationEvent::class.java) { e ->
			if (e.itemAbility == ItemAbilities.AXE_STRIP) {
				val stripped = ModBlocks.STRIPPED[e.state.block]
				if (stripped != null) {
					e.setFinalState(stripped.defaultBlockState().setValue(RotatedPillarBlock.AXIS, e.state.getValue(RotatedPillarBlock.AXIS)))
				}
			}
		}
	}
}
