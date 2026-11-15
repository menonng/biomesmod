package io.github.menonng.biomesmod.mixin

import io.github.menonng.biomesmod.ModBlocks
import net.minecraft.world.item.AxeItem
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.state.BlockState
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable
import java.util.Optional

/** An axe strips the mod's logs and woods (vanilla keeps its strippable blocks in a closed table). */
@Mixin(AxeItem::class)
abstract class AxeItemMixin {
	@Inject(method = ["getStripped"], at = [At("HEAD")], cancellable = true)
	private fun `biomesmod$strip`(state: BlockState, cir: CallbackInfoReturnable<Optional<BlockState>>) {
		val stripped = ModBlocks.STRIPPED[state.block] ?: return
		cir.returnValue = Optional.of(stripped.defaultBlockState().setValue(RotatedPillarBlock.AXIS, state.getValue(RotatedPillarBlock.AXIS)))
	}
}
