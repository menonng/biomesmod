package io.github.menonng.biomesmod;

import io.github.menonng.biomesmod.compat.BlockCompat;
import java.util.List;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/**
 * Natural blocks added for landscapes vanilla cannot dress convincingly. Used by the terrain shaper palettes,
 * landforms and surface rules of the matching biomes; all are also in the Natural Blocks creative tab.
 */
public final class ModBlocks {
	/** Salar de Uyuni, Salar de Tara, Lake Gairdner: bright crystalline salt. */
	public static final Block SALT_CRUST = BlockCompat.block("salt_crust",
			BlockBehaviour.Properties.of().mapColor(MapColor.SNOW).strength(0.6F).sound(SoundType.CALCITE));
	/** Pamukkale: layered white travertine of the terraces. */
	public static final Block TRAVERTINE = BlockCompat.block("travertine",
			BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_WHITE).strength(1.2F, 4.0F).sound(SoundType.CALCITE).requiresCorrectToolForDrops());
	/** Dallol: neon sulfur and salt crust around the acid pools. */
	public static final Block SULFUR_CRUST = BlockCompat.block("sulfur_crust",
			BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(0.8F).sound(SoundType.TUFF));
	/** Alkefjellet: dark columnar dolerite of the bird cliffs. */
	public static final Block DOLERITE = BlockCompat.block("dolerite",
			BlockBehaviour.Properties.of().mapColor(MapColor.DEEPSLATE).strength(2.5F, 6.0F).sound(SoundType.BASALT).requiresCorrectToolForDrops());

	public static final List<Block> ALL = List.of(SALT_CRUST, TRAVERTINE, SULFUR_CRUST, DOLERITE);

	private ModBlocks() {
	}

	static void register() {
		List<Item> items = List.of(BlockCompat.item("salt_crust", SALT_CRUST), BlockCompat.item("travertine", TRAVERTINE),
				BlockCompat.item("sulfur_crust", SULFUR_CRUST), BlockCompat.item("dolerite", DOLERITE));
		BlockCompat.naturalTab(items);
	}
}
