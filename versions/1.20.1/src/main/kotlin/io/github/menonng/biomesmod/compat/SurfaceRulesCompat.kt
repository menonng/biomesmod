package io.github.menonng.biomesmod.compat

import io.github.menonng.biomesmod.BiomesMod
import io.github.menonng.biomesmod.worldgen.ModSurfaceRules
import io.github.menonng.biomesmod.worldgen.Placements
import net.minecraft.world.level.levelgen.SurfaceRules
import terrablender.api.SurfaceRuleManager

/**
 * 1.21.1: TerraBlender takes a RuleSource, isBiome takes ResourceKeys directly and the 2D noise condition is
 * `noiseCondition`. Rules are built now, at TerraBlender init (blocks they use must be registered by then).
 */
object SurfaceRulesCompat {
	fun register(placements: Placements) {
		ModSurfaceRules.overworld(placements.overworldSurface, { SurfaceRules.isBiome(it) }, SurfaceRules::noiseCondition)?.let {
			SurfaceRuleManager.addSurfaceRules(SurfaceRuleManager.RuleCategory.OVERWORLD, BiomesMod.MOD_ID, it)
		}
		ModSurfaceRules.nether(placements.netherSurface, { SurfaceRules.isBiome(it) }, SurfaceRules::noiseCondition)?.let {
			SurfaceRuleManager.addSurfaceRules(SurfaceRuleManager.RuleCategory.NETHER, BiomesMod.MOD_ID, it)
		}
	}
}
