package io.github.menonng.biomesmod.mixin

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod
import com.llamalad7.mixinextras.injector.wrapoperation.Operation
import net.minecraft.world.level.levelgen.structure.Structure
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment
import net.minecraft.world.level.levelgen.structure.structures.JigsawStructure
import org.spongepowered.asm.mixin.Mixin
import java.util.Optional

/**
 * Villages and outposts in this mod's biomes only start on ground that is level enough for them. Vanilla relies on its
 * village biomes being flat; the sites of this mod carry ranges, canyon rims and cones, where the houses of a village
 * laid out from one height end up in the air on the low side and buried on the high side.
 */
@Mixin(JigsawStructure::class)
abstract class JigsawStructureMixin {
	@WrapMethod(method = ["findGenerationPoint"])
	private fun `biomesmod$levelGround`(context: Structure.GenerationContext, original: Operation<Optional<Structure.GenerationStub>>): Optional<Structure.GenerationStub> {
		if ((this as Any as Structure).terrainAdaptation() != TerrainAdjustment.BEARD_THIN) {
			return original.call(context)
		}
		return if (io.github.menonng.biomesmod.site.LevelGround.allows(context)) original.call(context) else Optional.empty()
	}
}
