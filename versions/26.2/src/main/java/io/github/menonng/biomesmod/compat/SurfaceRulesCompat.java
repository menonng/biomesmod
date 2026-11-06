package io.github.menonng.biomesmod.compat;

import io.github.menonng.biomesmod.BiomesMod;
import io.github.menonng.biomesmod.ModBiomes;
import io.github.menonng.biomesmod.worldgen.ModSurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceRules;
import terrablender.api.SurfaceRuleManager;

/** 26.2: TerraBlender takes a RuleBuilder (biome lookup -> RuleSource), and isBiome needs that lookup. */
public final class SurfaceRulesCompat {
	private SurfaceRulesCompat() {
	}

	public static void register() {
		SurfaceRuleManager.addSurfaceRules(SurfaceRuleManager.RuleCategory.NETHER, BiomesMod.MOD_ID,
				biomes -> ModSurfaceRules.nether(SurfaceRules.isBiome(biomes, ModBiomes.DANAKIL_FLATS)));
	}
}
