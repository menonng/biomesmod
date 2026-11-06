package io.github.menonng.biomesmod.worldgen;

import com.mojang.datafixers.util.Pair;
import io.github.menonng.biomesmod.ModBiomes;
import io.github.menonng.biomesmod.compat.BiomegenCompat;
import java.util.function.Consumer;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterList;
import terrablender.api.Region;
import terrablender.api.RegionType;

/** One generated nether region: the vanilla nether points plus mod biome points, so mod regions still blend with vanilla ones. */
public final class ModNetherRegion extends Region {
	private final Placements.NetherRegion data;

	public ModNetherRegion(Placements.NetherRegion data) {
		super(BiomegenCompat.id(data.name()), RegionType.NETHER, data.weight());
		this.data = data;
	}

	@Override
	public void addBiomes(Registry<Biome> registry, Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> mapper) {
		if (data.vanillaPoints()) {
			// Taken from the game itself (MultiNoiseBiomeSourceParameterList preset "nether"), not hard-coded.
			MultiNoiseBiomeSourceParameterList.knownPresets().get(MultiNoiseBiomeSourceParameterList.Preset.NETHER).values().forEach(mapper);
		}
		for (Placements.NetherPoint p : data.points()) {
			addBiome(mapper, Climate.parameters(p.temperature(), p.humidity(), p.continentalness(), p.erosion(), p.depth(), p.weirdness(), p.offset()),
					ModBiomes.key(p.biome()));
		}
	}
}
