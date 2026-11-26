package io.github.menonng.biomesmod.compat

import com.mojang.serialization.Codec
import com.mojang.serialization.MapCodec
import io.github.menonng.biomesmod.BiomesMod
import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.ColorRGBA
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.block.AmethystClusterBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.BonemealableBlock
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.ColoredFallingBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.FlowerBlock
import net.minecraft.world.level.block.PressurePlateBlock
import net.minecraft.world.level.block.SaplingBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.grower.TreeGrower
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockSetType
import net.minecraft.world.level.block.state.properties.WoodType
import net.minecraft.world.level.levelgen.DensityFunction
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature
import terrablender.api.EndBiomeRegistry
import java.util.Optional

typealias ChunkStatusCompat = net.minecraft.world.level.chunk.status.ChunkStatus

/** Version shim: block classes and constructors that changed shape between Minecraft versions (1.21+ shape). */
object BlockKinds {
	fun fenceGate(type: WoodType, p: BlockBehaviour.Properties): Block = FenceGateBlock(type, p)
	fun door(set: BlockSetType, p: BlockBehaviour.Properties): Block = DoorBlock(set, p)
	fun trapdoor(set: BlockSetType, p: BlockBehaviour.Properties): Block = TrapDoorBlock(set, p)
	fun pressurePlate(set: BlockSetType, p: BlockBehaviour.Properties): Block = PressurePlateBlock(set, p)
	fun button(set: BlockSetType, ticks: Int, p: BlockBehaviour.Properties): Block = ButtonBlock(set, ticks, p)

	/** Sand-like falling block whose falling particles have the colour [argb]. */
	fun sand(argb: Int, p: BlockBehaviour.Properties): Block = ColoredFallingBlock(ColorRGBA(argb), p)

	fun sapling(name: String, mega: Optional<ResourceKey<ConfiguredFeature<*, *>>>, tree: Optional<ResourceKey<ConfiguredFeature<*, *>>>,
		p: BlockBehaviour.Properties): Block = SaplingBlock(TreeGrower(name, mega, tree, Optional.empty()), p)

	internal fun effect(name: String) = when (name) {
		"regeneration" -> MobEffects.REGENERATION
		"night_vision" -> MobEffects.NIGHT_VISION
		"hunger" -> MobEffects.HUNGER
		else -> throw IllegalArgumentException(name)
	}
}

/** A flower that gives [effect] for [seconds] in suspicious stew. */
open class CompatFlower(effect: String, seconds: Float, p: BlockBehaviour.Properties) : FlowerBlock(BlockKinds.effect(effect), seconds, p)

/** A cluster on any face, like amethyst, [height] tall and [width] wide (in pixels). */
open class CompatCluster(height: Float, width: Float, p: BlockBehaviour.Properties) : AmethystClusterBlock(height, width, p)

/** A block that bone meal can grow; [canBonemeal] stands for the version's isValidBonemealTarget. */
abstract class CompatBonemealBlock(p: BlockBehaviour.Properties) : Block(p), BonemealableBlock {
	abstract fun canBonemeal(level: LevelReader, pos: BlockPos, state: BlockState): Boolean

	override fun isValidBonemealTarget(level: LevelReader, pos: BlockPos, state: BlockState): Boolean = canBonemeal(level, pos, state)
}

/** Version shim: codec plumbing (1.20.5+ dispatches to map codecs). */
object CodecCompat {
	fun <K : Any, V : Any> dispatch(key: Codec<K>, name: String, type: (V) -> K, codec: (K) -> MapCodec<out V>): Codec<V> =
		key.dispatch(name, { type(it) }, { codec(it) })

	fun <K : Any, V : Any> dispatchMap(key: Codec<K>, name: String, type: (V) -> K, codec: (K) -> MapCodec<out V>): MapCodec<V> =
		key.dispatchMap(name, { type(it) }, { codec(it) })

	fun registerDensityType(id: ResourceLocation, codec: MapCodec<out DensityFunction>) {
		Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, id, codec)
	}
}

/** Version shim: TerraBlender's End biome registry. */
object EndCompat {
	fun register(type: String, key: ResourceKey<Biome>, weight: Int) {
		when (type) {
			"highlands" -> EndBiomeRegistry.registerHighlandsBiome(key, weight)
			"midlands" -> EndBiomeRegistry.registerMidlandsBiome(key, weight)
			"edge" -> EndBiomeRegistry.registerEdgeBiome(key, weight)
			"islands" -> EndBiomeRegistry.registerIslandBiome(key, weight)
			else -> throw IllegalStateException("Unknown End biome type $type")
		}
	}
}
