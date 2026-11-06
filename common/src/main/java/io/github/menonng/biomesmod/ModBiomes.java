package io.github.menonng.biomesmod;

import io.github.menonng.biomesmod.compat.Ids;
import java.util.List;
import net.fabricmc.fabric.api.biome.v1.TheEndBiomes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;

/**
 * Biome keys + End placement. The biome definitions themselves are data-driven
 * (data/biomesmod/worldgen/biome/*.json) because their JSON schema differs per version.
 */
public final class ModBiomes {
	/** Dense chorus forest in the outer End highlands, with violet fog and portal motes. */
	public static final ResourceKey<Biome> CHORUS_THICKET = key("chorus_thicket");

	public static final List<ResourceKey<Biome>> ALL = List.of(CHORUS_THICKET);

	private ModBiomes() {
	}

	private static ResourceKey<Biome> key(String path) {
		return ResourceKey.create(Registries.BIOME, Ids.id(path));
	}

	static void init() {
		// Vanilla end_highlands has weight 1.0; 0.5 => roughly 1/3 of highland area.
		// Midlands/barrens default to the biome itself, so the thicket edges stay coherent.
		TheEndBiomes.addHighlandsBiome(CHORUS_THICKET, 0.5);
		BiomesMod.LOGGER.info("Registered {} End biome(s)", ALL.size());
	}
}
