package io.github.menonng.biomesmod.worldgen;

import io.github.menonng.biomesmod.ModBiomes;
import io.github.menonng.biomesmod.compat.BiomegenCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.placement.CaveSurface;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

/**
 * Surface rules built from the spec "surface" data in placements.json. Version-independent: each version's
 * SurfaceRulesCompat supplies the biome and noise conditions (their signatures differ) and registers the result
 * with TerraBlender. Mod rules run before the vanilla ones inside this mod's regions; anything not matched here
 * falls through to vanilla.
 *
 * <p>Per biome (first match wins):
 * <ul>
 * <li>{@code top_patches}, then {@code top}: the floor block. Overworld: only where the floor is not under water
 * (vanilla's {@code waterBlockCheck(-1, 0)}).</li>
 * <li>{@code underwater} (overworld): the floor block under water.</li>
 * <li>{@code under}: the blocks below the floor, {@code under_depth} deep or, without it, the vanilla variable
 * surface depth ({@code UNDER_FLOOR}). Also used for a wet floor when {@code underwater} is not set.</li>
 * </ul>
 * Overworld rules apply only near the surface ({@code abovePreliminarySurface}), so cave floors stay vanilla.
 * Nether rules apply on every floor, like the vanilla nether biomes.
 */
public final class ModSurfaceRules {
	private ModSurfaceRules() {
	}

	/** Version shim for noise conditions: noiseCondition (1.21.1) / noiseCondition2d (26.2). */
	@FunctionalInterface
	public interface NoiseCondition {
		SurfaceRules.ConditionSource of(ResourceKey<NormalNoise.NoiseParameters> noise, double min, double max);
	}

	public static SurfaceRules.RuleSource overworld(List<Placements.Surface> surfaces, Function<ResourceKey<Biome>, SurfaceRules.ConditionSource> isBiome,
			NoiseCondition noise) {
		return build(surfaces, true, isBiome, noise);
	}

	public static SurfaceRules.RuleSource nether(List<Placements.Surface> surfaces, Function<ResourceKey<Biome>, SurfaceRules.ConditionSource> isBiome,
			NoiseCondition noise) {
		return build(surfaces, false, isBiome, noise);
	}

	private static SurfaceRules.RuleSource build(List<Placements.Surface> surfaces, boolean overworld,
			Function<ResourceKey<Biome>, SurfaceRules.ConditionSource> isBiome, NoiseCondition noise) {
		List<SurfaceRules.RuleSource> perBiome = new ArrayList<>();
		for (Placements.Surface s : surfaces) {
			List<SurfaceRules.RuleSource> rules = new ArrayList<>();
			List<SurfaceRules.RuleSource> top = new ArrayList<>();
			for (Placements.Patch p : s.topPatches()) {
				ResourceKey<NormalNoise.NoiseParameters> key = ResourceKey.create(Registries.NOISE, BiomegenCompat.id(p.noise()));
				top.add(SurfaceRules.ifTrue(noise.of(key, p.min(), p.max()), state(p.block())));
			}
			if (s.top() != null) {
				top.add(state(s.top()));
			}
			if (!top.isEmpty()) {
				SurfaceRules.RuleSource topRule = sequence(top);
				rules.add(SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, overworld ? SurfaceRules.ifTrue(SurfaceRules.waterBlockCheck(-1, 0), topRule) : topRule));
			}
			if (overworld && s.underwater() != null) {
				rules.add(SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, state(s.underwater())));
			}
			if (s.under() != null) {
				SurfaceRules.ConditionSource depth = s.underDepth() > 0
						? SurfaceRules.stoneDepthCheck(s.underDepth(), false, CaveSurface.FLOOR)
						: SurfaceRules.UNDER_FLOOR;
				rules.add(SurfaceRules.ifTrue(depth, state(s.under())));
			}
			if (rules.isEmpty()) {
				continue;
			}
			SurfaceRules.RuleSource body = sequence(rules);
			if (overworld) {
				body = SurfaceRules.ifTrue(SurfaceRules.abovePreliminarySurface(), body);
			}
			perBiome.add(SurfaceRules.ifTrue(isBiome.apply(ModBiomes.key(s.biome())), body));
		}
		return perBiome.isEmpty() ? null : sequence(perBiome);
	}

	private static SurfaceRules.RuleSource sequence(List<SurfaceRules.RuleSource> rules) {
		return rules.size() == 1 ? rules.get(0) : SurfaceRules.sequence(rules.toArray(SurfaceRules.RuleSource[]::new));
	}

	private static SurfaceRules.RuleSource state(String blockId) {
		Block block = BuiltInRegistries.BLOCK.getOptional(BiomegenCompat.id(blockId))
				.orElseThrow(() -> new IllegalStateException("Unknown block " + blockId + " in " + Placements.RESOURCE));
		return SurfaceRules.state(block.defaultBlockState());
	}
}
