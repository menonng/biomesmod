package io.github.menonng.biomesmod.compat

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import io.github.menonng.biomesmod.BiomesMod
import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.RandomSource
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.AmethystClusterBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.BonemealableBlock
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.FlowerBlock
import net.minecraft.world.level.block.PressurePlateBlock
import net.minecraft.world.level.block.SandBlock
import net.minecraft.world.level.block.SaplingBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.grower.AbstractMegaTreeGrower
import net.minecraft.world.level.block.grower.AbstractTreeGrower
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockSetType
import net.minecraft.world.level.block.state.properties.WoodType
import net.minecraft.world.level.levelgen.DensityFunction
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature
import java.util.Optional

typealias ChunkStatusCompat = net.minecraft.world.level.chunk.ChunkStatus

/** Version shim: block classes and constructors that changed shape between Minecraft versions (1.20.1 shape). */
object BlockKinds {
	fun fenceGate(type: WoodType, p: BlockBehaviour.Properties): Block = FenceGateBlock(p, type)
	fun door(set: BlockSetType, p: BlockBehaviour.Properties): Block = DoorBlock(p, set)
	fun trapdoor(set: BlockSetType, p: BlockBehaviour.Properties): Block = TrapDoorBlock(p, set)
	fun pressurePlate(set: BlockSetType, p: BlockBehaviour.Properties): Block = PressurePlateBlock(PressurePlateBlock.Sensitivity.EVERYTHING, p, set)
	fun button(set: BlockSetType, ticks: Int, p: BlockBehaviour.Properties): Block = ButtonBlock(p, set, ticks, true)

	/** Sand-like falling block whose falling particles have the colour [argb]. */
	fun sand(argb: Int, p: BlockBehaviour.Properties): Block = SandBlock(argb, p)

	fun sapling(name: String, mega: Optional<ResourceKey<ConfiguredFeature<*, *>>>, tree: Optional<ResourceKey<ConfiguredFeature<*, *>>>,
		p: BlockBehaviour.Properties): Block {
		val grower = if (mega.isPresent) {
			object : AbstractMegaTreeGrower() {
				override fun getConfiguredFeature(random: RandomSource, flowers: Boolean): ResourceKey<ConfiguredFeature<*, *>>? = tree.orElse(null)
				override fun getConfiguredMegaFeature(random: RandomSource): ResourceKey<ConfiguredFeature<*, *>>? = mega.get()
			}
		} else {
			object : AbstractTreeGrower() {
				override fun getConfiguredFeature(random: RandomSource, flowers: Boolean): ResourceKey<ConfiguredFeature<*, *>>? = tree.orElse(null)
			}
		}
		return SaplingBlock(grower, p)
	}

	internal fun effect(name: String) = when (name) {
		"regeneration" -> MobEffects.REGENERATION
		"night_vision" -> MobEffects.NIGHT_VISION
		"hunger" -> MobEffects.HUNGER
		else -> throw IllegalArgumentException(name)
	}
}

/** A flower that gives [effect] for [seconds] in suspicious stew. */
open class CompatFlower(effect: String, seconds: Float, p: BlockBehaviour.Properties) : FlowerBlock(BlockKinds.effect(effect), seconds.toInt(), p)

/** A cluster on any face, like amethyst, [height] tall and [width] wide (in pixels). */
open class CompatCluster(height: Float, width: Float, p: BlockBehaviour.Properties) : AmethystClusterBlock(height.toInt(), width.toInt(), p)

/** A block that bone meal can grow; [canBonemeal] stands for the version's isValidBonemealTarget. */
abstract class CompatBonemealBlock(p: BlockBehaviour.Properties) : Block(p), BonemealableBlock {
	abstract fun canBonemeal(level: LevelReader, pos: BlockPos, state: BlockState): Boolean

	override fun isValidBonemealTarget(level: LevelReader, pos: BlockPos, state: BlockState, client: Boolean): Boolean = canBonemeal(level, pos, state)
}

/** Version shim: codec plumbing (1.20.1 dispatches to plain codecs). */
object CodecCompat {
	fun <K : Any, V : Any> dispatch(key: Codec<K>, name: String, type: (V) -> K, codec: (K) -> MapCodec<out V>): Codec<V> =
		key.dispatch(name, { type(it) }, { codec(it).codec() })

	fun <K : Any, V : Any> dispatchMap(key: Codec<K>, name: String, type: (V) -> K, codec: (K) -> MapCodec<out V>): MapCodec<V> =
		key.dispatchMap(name, { type(it) }, { codec(it).codec() })

	fun registerDensityType(id: ResourceLocation, codec: MapCodec<out DensityFunction>) {
		Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, id, codec.codec())
	}
}

/** Version shim: TerraBlender 3 (1.20.1) has no End biome registry, so the End biomes do not generate on 1.20.1. */
object EndCompat {
	private var warned = false

	fun register(type: String, key: ResourceKey<Biome>, weight: Int) {
		if (!warned) {
			warned = true
			BiomesMod.LOGGER.warn("TerraBlender for 1.20.1 cannot place End biomes; the mod's End biomes are left out on this version")
		}
	}
}
