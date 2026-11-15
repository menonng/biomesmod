package io.github.menonng.biomesmod.block

import io.github.menonng.biomesmod.compat.BlockCompat
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.FenceBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.PressurePlateBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.StairBlock
import net.minecraft.world.level.block.TrapDoorBlock
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.properties.BlockSetType
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument
import net.minecraft.world.level.block.state.properties.WoodType
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction

/**
 * The worked blocks of one of the mod's woods, as every vanilla wood has them: stripped log and wood, planks, stairs,
 * slab, fence, fence gate, door, trapdoor, pressure plate and button. Signs and boats are not part of it (they need
 * their own entities and renderers).
 *
 * The sounds and the way doors and buttons behave come from a vanilla wood ([set], [type]), so no new wood type is
 * registered.
 */
class WoodFamily(
	val name: String,
	val log: Block,
	val wood: Block,
	private val color: MapColor,
	private val sound: SoundType,
	set: BlockSetType,
	type: WoodType,
) {
	private fun props(strength: Float = 2.0f, resistance: Float = 3.0f): BlockBehaviour.Properties =
		BlockBehaviour.Properties.of().mapColor(color).instrument(NoteBlockInstrument.BASS).strength(strength, resistance).sound(sound).ignitedByLava()

	val strippedLog: Block = BlockCompat.register("stripped_${name}_log", props(2.0f, 2.0f), ::RotatedPillarBlock)
	val strippedWood: Block = BlockCompat.register("stripped_${name}_wood", props(2.0f, 2.0f), ::RotatedPillarBlock)
	val planks: Block = BlockCompat.block("${name}_planks", props())
	val stairs: Block = BlockCompat.register("${name}_stairs", props()) { StairBlock(planks.defaultBlockState(), it) }
	val slab: Block = BlockCompat.register("${name}_slab", props(), ::SlabBlock)
	val fence: Block = BlockCompat.register("${name}_fence", props(), ::FenceBlock)
	val fenceGate: Block = BlockCompat.register("${name}_fence_gate", props().forceSolidOn()) { FenceGateBlock(type, it) }
	val door: Block = BlockCompat.register("${name}_door", props(3.0f, 3.0f).noOcclusion().pushReaction(PushReaction.DESTROY)) { DoorBlock(set, it) }
	val trapdoor: Block = BlockCompat.register("${name}_trapdoor",
		props(3.0f, 3.0f).noOcclusion().isValidSpawn { _, _, _, _ -> false }) { TrapDoorBlock(set, it) }
	val pressurePlate: Block = BlockCompat.register("${name}_pressure_plate",
		BlockCompat.noCollision(props(0.5f, 0.5f).forceSolidOn()).pushReaction(PushReaction.DESTROY)) { PressurePlateBlock(set, it) }
	val button: Block = BlockCompat.register("${name}_button",
		BlockCompat.noCollision(BlockBehaviour.Properties.of().strength(0.5f).sound(sound)).pushReaction(PushReaction.DESTROY)) { ButtonBlock(set, 30, it) }

	/** Every block of the family with its id, in creative tab order. */
	val all: List<Pair<String, Block>> = listOf(
		"stripped_${name}_log" to strippedLog, "stripped_${name}_wood" to strippedWood, "${name}_planks" to planks,
		"${name}_stairs" to stairs, "${name}_slab" to slab, "${name}_fence" to fence, "${name}_fence_gate" to fenceGate,
		"${name}_door" to door, "${name}_trapdoor" to trapdoor, "${name}_pressure_plate" to pressurePlate, "${name}_button" to button,
	)
}
