package io.github.menonng.biomesmod.mixin

import io.github.menonng.biomesmod.NoFreeze
import net.minecraft.core.BlockPos
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.biome.Biome
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

/** Biomes listed in biomesmod/no_freeze.json keep open water at any height (hot springs, fjords, high salt lakes). */
@Mixin(Biome::class)
abstract class BiomeFreezeMixin {
	@Inject(method = ["shouldFreeze(Lnet/minecraft/world/level/LevelReader;Lnet/minecraft/core/BlockPos;Z)Z"], at = [At("HEAD")], cancellable = true)
	private fun `biomesmod$noFreeze`(level: LevelReader, pos: BlockPos, checkNeighbors: Boolean, cir: CallbackInfoReturnable<Boolean>) {
		if (NoFreeze.contains(this as Any as Biome)) {
			cir.returnValue = false
		}
	}
}
