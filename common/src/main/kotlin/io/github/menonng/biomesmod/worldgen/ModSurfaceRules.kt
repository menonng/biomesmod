package io.github.menonng.biomesmod.worldgen

import io.github.menonng.biomesmod.ModBiomes
import io.github.menonng.biomesmod.compat.BiomegenCompat
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.levelgen.VerticalAnchor
import net.minecraft.world.level.levelgen.SurfaceRules
import net.minecraft.world.level.levelgen.placement.CaveSurface
import net.minecraft.world.level.levelgen.synth.NormalNoise

/** Biome condition (version shim: isBiome takes a biome lookup in 26.2). */
typealias BiomeCondition = (ResourceKey<Biome>) -> SurfaceRules.ConditionSource

/** Noise condition (version shim: noiseCondition in 1.21.1, noiseCondition2d in 26.2). */
typealias NoiseCondition = (ResourceKey<NormalNoise.NoiseParameters>, Double, Double) -> SurfaceRules.ConditionSource

/**
 * Surface rules built from the spec "surface" data in placements.json. Version-independent: each version's
 * SurfaceRulesCompat supplies the biome and noise conditions (their signatures differ) and registers the result with
 * TerraBlender. Mod rules run before the vanilla ones inside this mod's regions; anything not matched here falls
 * through to vanilla.
 *
 * Per biome (first match wins):
 * - `top_patches`, then `top`: the floor block. Overworld: only where the floor is not under water (vanilla's
 *   `waterBlockCheck(-1, 0)`).
 * - `underwater` (overworld): the floor block under water.
 * - `under`: the blocks below the floor, `under_depth` deep or, without it, the vanilla variable surface depth
 *   (`UNDER_FLOOR`). Also used for a wet floor when `underwater` is not set.
 *
 * Overworld rules apply only near the surface (`abovePreliminarySurface`), so cave floors stay vanilla. Nether rules
 * apply on every floor, like the vanilla nether biomes.
 */
object ModSurfaceRules {
	fun overworld(surfaces: List<Placements.Surface>, isBiome: BiomeCondition, noise: NoiseCondition): SurfaceRules.RuleSource? =
		build(surfaces, true, isBiome, noise)

	fun nether(surfaces: List<Placements.Surface>, isBiome: BiomeCondition, noise: NoiseCondition): SurfaceRules.RuleSource? =
		build(surfaces, false, isBiome, noise)

	private fun build(surfaces: List<Placements.Surface>, overworld: Boolean, isBiome: BiomeCondition, noise: NoiseCondition): SurfaceRules.RuleSource? {
		val perBiome = ArrayList<SurfaceRules.RuleSource>()
		for (s in surfaces) {
			val rules = ArrayList<SurfaceRules.RuleSource>()
			if (overworld && s.shore != null) {
				// The strand: where the land of the biome meets the sea its own ground gives way to beach material.
				val band = SurfaceRules.ifTrue(SurfaceRules.yBlockCheck(VerticalAnchor.absolute(SHORE_BOTTOM), 0),
					SurfaceRules.ifTrue(SurfaceRules.not(SurfaceRules.yBlockCheck(VerticalAnchor.absolute(SHORE_TOP), 0)), state(s.shore)))
				rules.add(SurfaceRules.ifTrue(SurfaceRules.UNDER_FLOOR, band))
			}
			val top = ArrayList<SurfaceRules.RuleSource>()
			for (p in s.topPatches) {
				val key = ResourceKey.create(Registries.NOISE, BiomegenCompat.id(p.noise))
				top.add(SurfaceRules.ifTrue(noise(key, p.min, p.max), state(p.block)))
			}
			s.top?.let { top.add(state(it)) }
			if (top.isNotEmpty()) {
				val topRule = sequence(top)
				rules.add(SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR,
					if (overworld) SurfaceRules.ifTrue(SurfaceRules.waterBlockCheck(-1, 0), topRule) else topRule))
			}
			if (overworld && s.underwater != null) {
				rules.add(SurfaceRules.ifTrue(SurfaceRules.ON_FLOOR, state(s.underwater)))
			}
			if (s.under != null) {
				val depth = if (s.underDepth > 0) SurfaceRules.stoneDepthCheck(s.underDepth, false, CaveSurface.FLOOR) else SurfaceRules.UNDER_FLOOR
				rules.add(SurfaceRules.ifTrue(depth, state(s.under)))
			}
			if (rules.isEmpty()) {
				continue
			}
			var body = sequence(rules)
			if (overworld) {
				body = SurfaceRules.ifTrue(SurfaceRules.abovePreliminarySurface(), body)
			}
			perBiome.add(SurfaceRules.ifTrue(isBiome(ModBiomes.key(s.biome)), body))
		}
		return if (perBiome.isEmpty()) null else sequence(perBiome)
	}

	private const val SHORE_BOTTOM = 57
	private const val SHORE_TOP = 65

	private fun sequence(rules: List<SurfaceRules.RuleSource>): SurfaceRules.RuleSource =
		if (rules.size == 1) rules[0] else SurfaceRules.sequence(*rules.toTypedArray())

	private fun state(blockId: String): SurfaceRules.RuleSource {
		val block = BuiltInRegistries.BLOCK.getOptional(BiomegenCompat.id(blockId))
			.orElseThrow { IllegalStateException("Unknown block $blockId in ${Placements.RESOURCE}") }
		return SurfaceRules.state(block.defaultBlockState())
	}
}
