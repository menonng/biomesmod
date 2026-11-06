package io.github.menonng.biomesmod.worldgen;

import com.mojang.datafixers.util.Pair;
import io.github.menonng.biomesmod.ModBiomes;
import io.github.menonng.biomesmod.compat.Ids;
import java.util.function.Consumer;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import terrablender.api.Region;
import terrablender.api.RegionType;

/**
 * Vanilla overworld layout with some vanilla biomes swapped for mod biomes.
 * A swapped biome keeps the vanilla climate slot, so the terrain "feel" comes from the slot chosen:
 * Forest is temperate, humid, mid-inland, gentle-to-hilly ground.
 */
public final class ModOverworldRegion extends Region {
	public ModOverworldRegion() {
		super(Ids.id("overworld"), RegionType.OVERWORLD, 2);
	}

	@Override
	public void addBiomes(Registry<Biome> registry, Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> mapper) {
		addModifiedVanillaOverworldBiomes(mapper, builder -> builder.replaceBiome(Biomes.FOREST, ModBiomes.GOTJAWAL));
	}
}
