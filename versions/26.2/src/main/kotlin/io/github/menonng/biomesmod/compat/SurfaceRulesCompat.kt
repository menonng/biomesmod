package io.github.menonng.biomesmod.compat

import io.github.menonng.biomesmod.BiomesMod
import io.github.menonng.biomesmod.worldgen.ModSurfaceRules
import io.github.menonng.biomesmod.worldgen.Placements
import net.minecraft.world.level.levelgen.SurfaceRules
import terrablender.api.SurfaceRuleManager

/**
 * 26.2: TerraBlender takes a RuleBuilder (biome lookup -> RuleSource), isBiome needs that lookup and the 2D noise
 * condition is `noiseCondition2d`. Rules are built when the world's noise settings are created.
 */
object SurfaceRulesCompat {
	fun register(placements: Placements) {
		if (placements.overworldSurface.isNotEmpty()) {
			SurfaceRuleManager.addSurfaceRules(SurfaceRuleManager.RuleCategory.OVERWORLD, BiomesMod.MOD_ID) { biomes ->
				ModSurfaceRules.overworld(placements.overworldSurface, { SurfaceRules.isBiome(biomes, it) }, SurfaceRules::noiseCondition2d)
			}
		}
		if (placements.netherSurface.isNotEmpty()) {
			SurfaceRuleManager.addSurfaceRules(SurfaceRuleManager.RuleCategory.NETHER, BiomesMod.MOD_ID) { biomes ->
				ModSurfaceRules.nether(placements.netherSurface, { SurfaceRules.isBiome(biomes, it) }, SurfaceRules::noiseCondition2d)
			}
		}
	}
}
