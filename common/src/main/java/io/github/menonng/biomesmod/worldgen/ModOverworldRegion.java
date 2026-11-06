package io.github.menonng.biomesmod.worldgen;

import com.mojang.datafixers.util.Pair;
import io.github.menonng.biomesmod.ModBiomes;
import io.github.menonng.biomesmod.compat.BiomegenCompat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import terrablender.api.Region;
import terrablender.api.RegionType;

/**
 * One generated overworld region: the vanilla overworld layout with some vanilla biomes swapped for mod biomes.
 * A swapped biome keeps the vanilla climate slot, so its terrain "feel" comes from the slot it takes
 * (spec placement.replace). The generator packs biomes so that no slot is replaced twice in one region.
 */
public final class ModOverworldRegion extends Region {
	private final Map<ResourceKey<Biome>, ResourceKey<Biome>> replacements = new LinkedHashMap<>();

	public ModOverworldRegion(Placements.OverworldRegion data) {
		super(BiomegenCompat.id(data.name()), RegionType.OVERWORLD, data.weight());
		data.replace().forEach((vanilla, mod) -> replacements.put(ModBiomes.key(vanilla), ModBiomes.key(mod)));
	}

	@Override
	public void addBiomes(Registry<Biome> registry, Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> mapper) {
		addModifiedVanillaOverworldBiomes(mapper, builder -> replacements.forEach(builder::replaceBiome));
	}
}
