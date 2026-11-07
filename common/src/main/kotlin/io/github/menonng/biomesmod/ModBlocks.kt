package io.github.menonng.biomesmod

import io.github.menonng.biomesmod.compat.BlockCompat
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor

/**
 * Natural blocks added for landscapes vanilla cannot dress convincingly. Used by the terrain shaper palettes,
 * landforms and surface rules of the matching biomes; all are also in the Natural Blocks creative tab.
 */
object ModBlocks {
	/** Salar de Uyuni, Salar de Tara, Lake Gairdner: bright crystalline salt. */
	@JvmField
	val SALT_CRUST: Block = BlockCompat.block("salt_crust",
		BlockBehaviour.Properties.of().mapColor(MapColor.SNOW).strength(0.6f).sound(SoundType.CALCITE))

	/** Pamukkale: layered white travertine of the terraces. */
	@JvmField
	val TRAVERTINE: Block = BlockCompat.block("travertine",
		BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_WHITE).strength(1.2f, 4.0f).sound(SoundType.CALCITE).requiresCorrectToolForDrops())

	/** Dallol: neon sulfur and salt crust around the acid pools. */
	@JvmField
	val SULFUR_CRUST: Block = BlockCompat.block("sulfur_crust",
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(0.8f).sound(SoundType.TUFF))

	/** Alkefjellet: dark columnar dolerite of the bird cliffs. */
	@JvmField
	val DOLERITE: Block = BlockCompat.block("dolerite",
		BlockBehaviour.Properties.of().mapColor(MapColor.DEEPSLATE).strength(2.5f, 6.0f).sound(SoundType.BASALT).requiresCorrectToolForDrops())

	@JvmField
	val ALL: List<Block> = listOf(SALT_CRUST, TRAVERTINE, SULFUR_CRUST, DOLERITE)

	internal fun register() {
		BlockCompat.naturalTab(listOf(
			BlockCompat.item("salt_crust", SALT_CRUST), BlockCompat.item("travertine", TRAVERTINE),
			BlockCompat.item("sulfur_crust", SULFUR_CRUST), BlockCompat.item("dolerite", DOLERITE)))
	}
}
