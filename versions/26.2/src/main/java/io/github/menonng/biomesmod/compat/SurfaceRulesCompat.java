package io.github.menonng.biomesmod.compat;

import io.github.menonng.biomesmod.BiomesMod;
import io.github.menonng.biomesmod.worldgen.ModSurfaceRules;
import io.github.menonng.biomesmod.worldgen.Placements;
import net.minecraft.world.level.levelgen.SurfaceRules;
import terrablender.api.SurfaceRuleManager;

/**
 * 26.2: TerraBlender takes a RuleBuilder (biome lookup -> RuleSource), isBiome needs that lookup and the 2D noise
 * condition is {@code noiseCondition2d}. Rules are built when the world's noise settings are created.
 */
public final class SurfaceRulesCompat {
	private SurfaceRulesCompat() {
	}

	public static void register(Placements placements) {
		if (!placements.overworldSurface().isEmpty()) {
			SurfaceRuleManager.addSurfaceRules(SurfaceRuleManager.RuleCategory.OVERWORLD, BiomesMod.MOD_ID,
					biomes -> ModSurfaceRules.overworld(placements.overworldSurface(), key -> SurfaceRules.isBiome(biomes, key),
							SurfaceRules::noiseCondition2d));
		}
		if (!placements.netherSurface().isEmpty()) {
			SurfaceRuleManager.addSurfaceRules(SurfaceRuleManager.RuleCategory.NETHER, BiomesMod.MOD_ID,
					biomes -> ModSurfaceRules.nether(placements.netherSurface(), key -> SurfaceRules.isBiome(biomes, key),
							SurfaceRules::noiseCondition2d));
		}
	}
}
