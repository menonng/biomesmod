package io.github.menonng.biomesmod.neoforge.mixin

import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.FireBlock
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Invoker

/** FireBlock.setFlammable is private in vanilla; Fabric has a registry for it, NeoForge goes through this invoker. */
@Mixin(FireBlock::class)
interface FireBlockAccess {
	@Invoker("setFlammable")
	fun biomesmod_setFlammable(block: Block, encouragement: Int, flammability: Int)
}
