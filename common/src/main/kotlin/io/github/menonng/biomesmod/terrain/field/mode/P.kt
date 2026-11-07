package io.github.menonng.biomesmod.terrain.field.mode

import io.github.menonng.biomesmod.compat.LandformCompat
import io.github.menonng.biomesmod.terrain.field.FieldPalette
import io.github.menonng.biomesmod.terrain.util.StrataPalette
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import java.util.Optional

/** Small builders for mode default palettes (vanilla blocks only, so they exist in every version). */
internal object P {
	fun s(b: Any): BlockState = if (b is Block) b.defaultBlockState() else id(b as String)

	/** Block by id: needed for dyed blocks, which are separate fields in 1.21.1 but ColorCollections in 26.2. */
	fun id(id: String): BlockState = LandformCompat.block(if (id.indexOf(':') < 0) "minecraft:$id" else id)

	/** layers(block, depth, block, depth, ...) */
	fun layers(vararg blockDepth: Any): List<FieldPalette.Layer> =
		(blockDepth.indices step 2).map { FieldPalette.Layer(s(blockDepth[it]), blockDepth[it + 1] as Int) }

	/** strata(block, thickness, block, thickness, ...) */
	fun strata(vararg blockThickness: Any): StrataPalette =
		StrataPalette(Optional.empty(), 1, (blockThickness.indices step 2).map { StrataPalette.Layer(s(blockThickness[it]), blockThickness[it + 1] as Int) }, 0)

	fun palette(top: List<FieldPalette.Layer>?, underwater: List<FieldPalette.Layer>?, strata: StrataPalette?, cliffSlope: Int): FieldPalette =
		FieldPalette(Optional.ofNullable(top), Optional.ofNullable(underwater), Optional.ofNullable(strata), listOf(), Optional.of(cliffSlope), Optional.empty())
}
