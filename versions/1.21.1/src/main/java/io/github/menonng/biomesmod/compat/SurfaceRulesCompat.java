package io.github.menonng.biomesmod.compat;

import io.github.menonng.biomesmod.BiomesMod;
import io.github.menonng.biomesmod.ModBiomes;
import io.github.menonng.biomesmod.worldgen.ModSurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceRules;
import terrablender.api.SurfaceRuleManager;

/** 1.21.1: TerraBlender takes a RuleSource, and isBiome takes ResourceKeys directly. */
public final class SurfaceRulesCompat {
	private SurfaceRulesCompat() {
	}

	public static void register() {
		SurfaceRuleManager.addSurfaceRules(SurfaceRuleManager.RuleCategory.NETHER, BiomesMod.MOD_ID,
				ModSurfaceRules.nether(SurfaceRules.isBiome(ModBiomes.DANAKIL_FLATS)));
	}
}
