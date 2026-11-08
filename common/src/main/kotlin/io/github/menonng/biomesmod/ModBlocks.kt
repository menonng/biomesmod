package io.github.menonng.biomesmod

import io.github.menonng.biomesmod.block.GhostwoodSaplingBlock
import io.github.menonng.biomesmod.block.GlowClusterBlock
import io.github.menonng.biomesmod.block.HangingPlantBlock
import io.github.menonng.biomesmod.block.VentBlock
import io.github.menonng.biomesmod.block.VentBlockEntity
import io.github.menonng.biomesmod.block.WispLogBlock
import io.github.menonng.biomesmod.compat.BlockCompat
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.util.ColorRGBA
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.level.block.AmethystClusterBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.CarpetBlock
import net.minecraft.world.level.block.ColoredFallingBlock
import net.minecraft.world.level.block.FlowerBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction

/**
 * Natural blocks added for landscapes vanilla cannot dress convincingly. Used by the terrain shaper palettes,
 * landforms, trees and surface rules of the matching biomes; all are also in the Natural Blocks creative tab.
 * Textures and models come from tools/blocks/make_blocks.py.
 */
object ModBlocks {
	private fun stone(color: MapColor, hardness: Float, sound: SoundType): BlockBehaviour.Properties =
		BlockBehaviour.Properties.of().mapColor(color).strength(hardness, hardness * 3).sound(sound).requiresCorrectToolForDrops()

	private fun log(color: MapColor, sound: SoundType): BlockBehaviour.Properties =
		BlockBehaviour.Properties.of().mapColor(color).strength(2.0f).sound(sound).ignitedByLava()

	private fun leavesProps(color: MapColor, sound: SoundType): BlockBehaviour.Properties =
		BlockBehaviour.Properties.of().mapColor(color).strength(0.2f).randomTicks().sound(sound).noOcclusion()
			.isValidSpawn { _, _, _, _ -> false }.isSuffocating { _, _, _ -> false }.isViewBlocking { _, _, _ -> false }
			.isRedstoneConductor { _, _, _ -> false }.ignitedByLava().pushReaction(PushReaction.DESTROY)

	private fun plant(color: MapColor): BlockBehaviour.Properties =
		BlockCompat.plantProperties().mapColor(color).sound(SoundType.GRASS).offsetType(BlockBehaviour.OffsetType.XZ)
			.pushReaction(PushReaction.DESTROY)

	// ---- existing ----

	/** Salar de Uyuni, Salar de Tara, Lake Gairdner: bright crystalline salt. */
	@JvmField
	val SALT_CRUST: Block = BlockCompat.block("salt_crust",
		BlockBehaviour.Properties.of().mapColor(MapColor.SNOW).strength(0.6f).sound(SoundType.CALCITE))

	/** Pamukkale: layered white travertine of the terraces. */
	@JvmField
	val TRAVERTINE: Block = BlockCompat.block("travertine", stone(MapColor.TERRACOTTA_WHITE, 1.2f, SoundType.CALCITE))

	/** Dallol: neon sulfur and salt crust around the acid pools. */
	@JvmField
	val SULFUR_CRUST: Block = BlockCompat.block("sulfur_crust",
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(0.8f).sound(SoundType.TUFF))

	/** Alkefjellet: dark columnar dolerite of the bird cliffs. */
	@JvmField
	val DOLERITE: Block = BlockCompat.block("dolerite", stone(MapColor.DEEPSLATE, 2.5f, SoundType.BASALT))

	// ---- Socotra ----

	/** Dracaena cinnabari: smooth silver-grey bark with leaf-scar rings; the cut face bleeds red resin. */
	@JvmField
	val DRAGON_BLOOD_LOG: Block = BlockCompat.register("dragon_blood_log", log(MapColor.TERRACOTTA_LIGHT_GRAY, SoundType.WOOD), ::RotatedPillarBlock)

	@JvmField
	val DRAGON_BLOOD_WOOD: Block = BlockCompat.register("dragon_blood_wood", log(MapColor.TERRACOTTA_LIGHT_GRAY, SoundType.WOOD), ::RotatedPillarBlock)

	/** The dense umbrella of stiff, upturned sword leaves. */
	@JvmField
	val DRAGON_BLOOD_LEAVES: Block = BlockCompat.leaves("dragon_blood_leaves", leavesProps(MapColor.PLANT, SoundType.AZALEA_LEAVES), -0xb18bc7)

	/** Adenium socotranum: the swollen grey bottle trunk crowned with pink flowers. */
	@JvmField
	val DESERT_ROSE: Block = BlockCompat.register("desert_rose", plant(MapColor.COLOR_PINK)) { FlowerBlock(MobEffects.REGENERATION, 4f, it) }

	// ---- geothermal ----

	/** Siliceous sinter of geyser cones and hot-spring aprons: pearly, knobbly, grey-white. */
	@JvmField
	val GEYSERITE: Block = BlockCompat.block("geyserite", stone(MapColor.TERRACOTTA_WHITE, 1.4f, SoundType.CALCITE))

	@JvmField
	val GEYSER_VENT: Block = BlockCompat.register("geyser_vent", stone(MapColor.TERRACOTTA_WHITE, 1.4f, SoundType.CALCITE)) { VentBlock(it, VentBlock.GEYSER) }

	@JvmField
	val STEAM_VENT: Block = BlockCompat.register("steam_vent", stone(MapColor.COLOR_YELLOW, 1.4f, SoundType.TUFF)) { VentBlock(it, VentBlock.STEAM) }

	/** Microbial mats of hot-spring runoff, hottest to coolest: orange, yellow, green. */
	@JvmField
	val THERMAL_MAT_ORANGE: Block = BlockCompat.block("thermal_mat_orange",
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(0.7f).sound(SoundType.MUD))

	@JvmField
	val THERMAL_MAT_YELLOW: Block = BlockCompat.block("thermal_mat_yellow",
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(0.7f).sound(SoundType.MUD))

	@JvmField
	val THERMAL_MAT_GREEN: Block = BlockCompat.block("thermal_mat_green",
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GREEN).strength(0.7f).sound(SoundType.MUD))

	// ---- sand, ash, scoria ----

	/** Lençóis Maranhenses: pure white quartz sand (replaces white concrete powder). */
	@JvmField
	val WHITE_SAND: Block = BlockCompat.register("white_sand",
		BlockBehaviour.Properties.of().mapColor(MapColor.SNOW).strength(0.5f).sound(SoundType.SAND)) { ColoredFallingBlock(ColorRGBA(-0x50709), it) }

	/** Fresh tephra on volcano flanks. */
	@JvmField
	val VOLCANIC_ASH: Block = BlockCompat.register("volcanic_ash",
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).strength(0.5f).sound(SoundType.SAND)) { ColoredFallingBlock(ColorRGBA(-0xb5b7b9), it) }

	/** Red-brown vesicular cinder of scoria cones and lava crusts. */
	@JvmField
	val SCORIA: Block = BlockCompat.block("scoria", stone(MapColor.TERRACOTTA_RED, 1.2f, SoundType.TUFF))

	// ---- white old-growth forest ----

	@JvmField
	val GHOSTWOOD_LOG: Block = BlockCompat.register("ghostwood_log", log(MapColor.QUARTZ, SoundType.CHERRY_WOOD), ::RotatedPillarBlock)

	@JvmField
	val GHOSTWOOD_WOOD: Block = BlockCompat.register("ghostwood_wood", log(MapColor.QUARTZ, SoundType.CHERRY_WOOD), ::RotatedPillarBlock)

	@JvmField
	val GHOSTWOOD_LEAVES: Block = BlockCompat.leaves("ghostwood_leaves", leavesProps(MapColor.SNOW, SoundType.CHERRY_LEAVES), -0x2a201d)

	/** Silver moss carpeting the white forest floor. */
	@JvmField
	val PALE_MOSS: Block = BlockCompat.block("pale_moss",
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_GRAY).strength(0.1f).sound(SoundType.MOSS))

	/** Thin silver moss over logs, boulders and the forest floor. */
	@JvmField
	val PALE_MOSS_CARPET: Block = BlockCompat.register("pale_moss_carpet",
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_GRAY).strength(0.1f).sound(SoundType.MOSS_CARPET).pushReaction(PushReaction.DESTROY), ::CarpetBlock)

	/** Beard lichen (Usnea) hanging in pale strands from the limbs. */
	@JvmField
	val SILVER_BEARD: Block = BlockCompat.register("silver_beard",
		BlockCompat.plantProperties().mapColor(MapColor.COLOR_LIGHT_GRAY).sound(SoundType.HANGING_ROOTS).pushReaction(PushReaction.DESTROY)) { HangingPlantBlock(it, null) }

	/** Faintly glowing shelf fungus on old trunks; pale spores drift off it. */
	@JvmField
	val MOON_BRACKET: Block = BlockCompat.register("moon_bracket",
		BlockBehaviour.Properties.of().mapColor(MapColor.QUARTZ).forceSolidOn().noOcclusion().sound(SoundType.FUNGUS).strength(0.3f).lightLevel { 6 }
			.pushReaction(PushReaction.DESTROY)) { GlowClusterBlock(4f, 3f, it, { ParticleTypes.WHITE_ASH }, 6) }

	/** A white woodland flower that glows softly at night. */
	@JvmField
	val MOONPETAL: Block = BlockCompat.register("moonpetal", plant(MapColor.SNOW).lightLevel { 5 }) { FlowerBlock(MobEffects.NIGHT_VISION, 8f, it) }

	/** A hollow in an ancient trunk, breathing out pale wisps. */
	@JvmField
	val GHOSTWOOD_HOLLOW: Block = BlockCompat.register("ghostwood_hollow", log(MapColor.QUARTZ, SoundType.CHERRY_WOOD).lightLevel { 4 }) {
		WispLogBlock(it) { ParticleTypes.END_ROD }
	}

	/** Nine in a 3x3 square grow into an ancient ghostwood. */
	@JvmField
	val GHOSTWOOD_SAPLING: Block = BlockCompat.register("ghostwood_sapling",
		BlockCompat.plantProperties().randomTicks().mapColor(MapColor.QUARTZ).sound(SoundType.CHERRY_SAPLING).pushReaction(PushReaction.DESTROY), ::GhostwoodSaplingBlock)

	@JvmField
	val PALE_FERN: Block = BlockCompat.register("pale_fern", plant(MapColor.COLOR_LIGHT_GRAY)) { FlowerBlock(MobEffects.NIGHT_VISION, 5f, it) }

	// ---- giant tree forest ----

	@JvmField
	val REDWOOD_LOG: Block = BlockCompat.register("redwood_log", log(MapColor.TERRACOTTA_RED, SoundType.WOOD), ::RotatedPillarBlock)

	@JvmField
	val REDWOOD_WOOD: Block = BlockCompat.register("redwood_wood", log(MapColor.TERRACOTTA_RED, SoundType.WOOD), ::RotatedPillarBlock)

	@JvmField
	val REDWOOD_LEAVES: Block = BlockCompat.leaves("redwood_leaves", leavesProps(MapColor.PLANT, SoundType.GRASS), -0xd0b5d4)

	// ---- glacier ----

	/** Old glacier ice: deep blue, bubble-streaked, never melts. */
	@JvmField
	val GLACIER_ICE: Block = BlockCompat.block("glacier_ice",
		BlockBehaviour.Properties.of().mapColor(MapColor.ICE).strength(2.8f).friction(0.989f).sound(SoundType.GLASS))

	// ---- star abyss ----

	@JvmField
	val ABYSS_STONE: Block = BlockCompat.block("abyss_stone", stone(MapColor.COLOR_BLACK, 3.0f, SoundType.DEEPSLATE))

	@JvmField
	val STAR_CRYSTAL: Block = BlockCompat.block("star_crystal",
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE).strength(1.5f).sound(SoundType.AMETHYST).lightLevel { 12 }
			.requiresCorrectToolForDrops())

	@JvmField
	val STAR_CRYSTAL_CLUSTER: Block = BlockCompat.register("star_crystal_cluster",
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE).forceSolidOn().noOcclusion().sound(SoundType.AMETHYST_CLUSTER)
			.strength(1.5f).lightLevel { 9 }.pushReaction(PushReaction.DESTROY)) { AmethystClusterBlock(7f, 3f, it) }

	/** Glittering star-blue sand of the abyss floors. */
	@JvmField
	val STARDUST: Block = BlockCompat.register("stardust",
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLUE).strength(0.5f).sound(SoundType.SAND).lightLevel { 3 }) { ColoredFallingBlock(ColorRGBA(-0xd5c78f), it) }

	/** Abyss stone shot through with glowing veins. */
	@JvmField
	val STARLIT_ABYSS_STONE: Block = BlockCompat.block("starlit_abyss_stone", stone(MapColor.COLOR_BLACK, 3.0f, SoundType.DEEPSLATE).lightLevel { 5 })

	/** A glowing flower that grows on any face, ceilings included. */
	@JvmField
	val STAR_BLOOM: Block = BlockCompat.register("star_bloom",
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_CYAN).forceSolidOn().noOcclusion().sound(SoundType.SPORE_BLOSSOM)
			.strength(0.2f).lightLevel { 10 }.pushReaction(PushReaction.DESTROY)) { GlowClusterBlock(5f, 3f, it, { ParticleTypes.END_ROD }, 14) }

	/** Glowing roots hanging under the islands. */
	@JvmField
	val ASTRAL_ROOTS: Block = BlockCompat.register("astral_roots",
		BlockCompat.plantProperties().mapColor(MapColor.COLOR_LIGHT_BLUE).sound(SoundType.HANGING_ROOTS).lightLevel { 6 }.pushReaction(PushReaction.DESTROY)) {
		HangingPlantBlock(it) { ParticleTypes.END_ROD }
	}

	/** A spring of starlight: steps into it rise gently towards the floating isles. */
	@JvmField
	val STAR_SPRING: Block = BlockCompat.register("star_spring", stone(MapColor.COLOR_LIGHT_BLUE, 3.0f, SoundType.AMETHYST).lightLevel { 13 }) {
		VentBlock(it, VentBlock.STAR)
	}

	lateinit var VENT_ENTITY: BlockEntityType<VentBlockEntity>
		private set

	@JvmField
	val ALL: List<Block> = listOf(
		SALT_CRUST, TRAVERTINE, SULFUR_CRUST, DOLERITE,
		DRAGON_BLOOD_LOG, DRAGON_BLOOD_WOOD, DRAGON_BLOOD_LEAVES, DESERT_ROSE,
		GEYSERITE, GEYSER_VENT, STEAM_VENT, THERMAL_MAT_ORANGE, THERMAL_MAT_YELLOW, THERMAL_MAT_GREEN,
		WHITE_SAND, VOLCANIC_ASH, SCORIA,
		GHOSTWOOD_LOG, GHOSTWOOD_WOOD, GHOSTWOOD_LEAVES, PALE_MOSS, PALE_FERN,
		REDWOOD_LOG, REDWOOD_WOOD, REDWOOD_LEAVES,
		GLACIER_ICE, ABYSS_STONE, STAR_CRYSTAL, STAR_CRYSTAL_CLUSTER,
		GHOSTWOOD_SAPLING, PALE_MOSS_CARPET, SILVER_BEARD, MOON_BRACKET, MOONPETAL, GHOSTWOOD_HOLLOW,
		STARDUST, STARLIT_ABYSS_STONE, STAR_BLOOM, ASTRAL_ROOTS, STAR_SPRING,
	)

	/** Blocks drawn with cut-out textures (1.21.1 needs them registered on the client). */
	internal val CUTOUT: List<Block> get() = listOf(DESERT_ROSE, PALE_FERN, GHOSTWOOD_SAPLING, STAR_CRYSTAL_CLUSTER, SILVER_BEARD, MOON_BRACKET, MOONPETAL, STAR_BLOOM,
		ASTRAL_ROOTS)
	internal val CUTOUT_MIPPED: List<Block> get() = listOf(DRAGON_BLOOD_LEAVES, GHOSTWOOD_LEAVES, REDWOOD_LEAVES)

	internal fun register() {
		VENT_ENTITY = BlockCompat.blockEntity("vent", ::VentBlockEntity, GEYSER_VENT, STEAM_VENT, STAR_SPRING)
		val ids = mapOf(
			SALT_CRUST to "salt_crust", TRAVERTINE to "travertine", SULFUR_CRUST to "sulfur_crust", DOLERITE to "dolerite",
			DRAGON_BLOOD_LOG to "dragon_blood_log", DRAGON_BLOOD_WOOD to "dragon_blood_wood", DRAGON_BLOOD_LEAVES to "dragon_blood_leaves",
			DESERT_ROSE to "desert_rose", GEYSERITE to "geyserite", GEYSER_VENT to "geyser_vent", STEAM_VENT to "steam_vent",
			THERMAL_MAT_ORANGE to "thermal_mat_orange", THERMAL_MAT_YELLOW to "thermal_mat_yellow", THERMAL_MAT_GREEN to "thermal_mat_green",
			WHITE_SAND to "white_sand", VOLCANIC_ASH to "volcanic_ash", SCORIA to "scoria",
			GHOSTWOOD_LOG to "ghostwood_log", GHOSTWOOD_WOOD to "ghostwood_wood", GHOSTWOOD_LEAVES to "ghostwood_leaves",
			PALE_MOSS to "pale_moss", PALE_FERN to "pale_fern",
			REDWOOD_LOG to "redwood_log", REDWOOD_WOOD to "redwood_wood", REDWOOD_LEAVES to "redwood_leaves",
			GLACIER_ICE to "glacier_ice", ABYSS_STONE to "abyss_stone", STAR_CRYSTAL to "star_crystal", STAR_CRYSTAL_CLUSTER to "star_crystal_cluster",
			GHOSTWOOD_SAPLING to "ghostwood_sapling", PALE_MOSS_CARPET to "pale_moss_carpet", SILVER_BEARD to "silver_beard", MOON_BRACKET to "moon_bracket", MOONPETAL to "moonpetal",
			GHOSTWOOD_HOLLOW to "ghostwood_hollow", STARDUST to "stardust", STARLIT_ABYSS_STONE to "starlit_abyss_stone", STAR_BLOOM to "star_bloom",
			ASTRAL_ROOTS to "astral_roots", STAR_SPRING to "star_spring",
		)
		BlockCompat.naturalTab(ALL.map { BlockCompat.item(ids.getValue(it), it) })
	}
}
