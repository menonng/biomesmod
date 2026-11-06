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

/** Vanilla nether biome points plus mod biomes, so mod regions still blend with vanilla ones. */
public final class ModNetherRegion extends Region {
	public ModNetherRegion() {
		super(Ids.id("nether"), RegionType.NETHER, 2);
	}

	@Override
	public void addBiomes(Registry<Biome> registry, Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> mapper) {
		// Vanilla nether points: temperature, humidity, ..., offset.
		addBiome(mapper, Climate.parameters(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F), Biomes.NETHER_WASTES);
		addBiome(mapper, Climate.parameters(0.0F, -0.5F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F), Biomes.SOUL_SAND_VALLEY);
		addBiome(mapper, Climate.parameters(0.4F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F), Biomes.CRIMSON_FOREST);
		addBiome(mapper, Climate.parameters(0.0F, 0.5F, 0.0F, 0.0F, 0.0F, 0.0F, 0.375F), Biomes.WARPED_FOREST);
		addBiome(mapper, Climate.parameters(-0.5F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.175F), Biomes.BASALT_DELTAS);
		// Hot and dry corner, beyond crimson forest.
		addBiome(mapper, Climate.parameters(0.75F, -0.35F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F), ModBiomes.DANAKIL_FLATS);
	}
}
