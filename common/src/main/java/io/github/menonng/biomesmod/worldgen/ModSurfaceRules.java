package io.github.menonng.biomesmod.worldgen;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.SurfaceRules;

/**
 * Version-independent surface rule shapes. Each version's SurfaceRulesCompat supplies the biome
 * condition (SurfaceRules.isBiome changed signature in 26.x) and registers the rules with TerraBlender.
 */
public final class ModSurfaceRules {
	private ModSurfaceRules() {
	}

	/** Danakil Flats: white salt-crust floor (calcite) over a basalt layer. */
	public static SurfaceRules.RuleSource nether(SurfaceRules.ConditionSource isDanakil) {
		return SurfaceRules.ifTrue(isDanakil, SurfaceRules.sequence(
				SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, SurfaceRules.state(Blocks.CALCITE.defaultBlockState())),
				SurfaceRules.ifTrue(SurfaceRules.UNDER_FLOOR, SurfaceRules.state(Blocks.BASALT.defaultBlockState()))));
	}
}
