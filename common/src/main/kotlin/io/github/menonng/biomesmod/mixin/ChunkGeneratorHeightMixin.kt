package io.github.menonng.biomesmod.mixin

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import io.github.menonng.biomesmod.terrain.landform.LandformHeights
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator
import net.minecraft.world.level.levelgen.RandomState
import org.spongepowered.asm.mixin.Mixin

/** While a village is being planned, height queries include the landforms that will stand there (see `LandformHeights`). */
@Mixin(NoiseBasedChunkGenerator::class)
abstract class ChunkGeneratorHeightMixin {
	@WrapMethod(method = ["getBaseHeight"])
	private fun `biomesmod$landformHeight`(x: Int, z: Int, type: Heightmap.Types, heights: LevelHeightAccessor, random: RandomState, original: Operation<Int>): Int =
		LandformHeights.adjust(this as Any as ChunkGenerator, random, heights, x, z, original.call(x, z, type, heights, random))
}
