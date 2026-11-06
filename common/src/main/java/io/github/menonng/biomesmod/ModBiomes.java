package io.github.menonng.biomesmod;

import io.github.menonng.biomesmod.compat.BiomegenCompat;
import io.github.menonng.biomesmod.worldgen.Placements;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * All mod biomes and the dimension each one generates in, read from the generated placement data
 * (common/src/main/resources/biomesmod/placements.json, written by tools/biomegen from specs/).
 * Biome definitions are generated too (data/biomesmod/worldgen/biome/*.json, one set per version).
 * Placement for all dimensions is registered in {@link io.github.menonng.biomesmod.worldgen.TerraBlenderEntry}.
 */
public final class ModBiomes {
	public record Entry(ResourceKey<Biome> key, ResourceKey<Level> dimension) {
	}

	/** Every mod biome, overworld first, then nether, then End (each sorted by id). */
	public static final List<Entry> ALL = Placements.get().biomes().stream()
			.map(b -> new Entry(key(b.id()), level(b.dimension())))
			.toList();

	private ModBiomes() {
	}

	/** Biome key from a namespaced id such as "biomesmod:gotjawal" or "minecraft:forest". */
	public static ResourceKey<Biome> key(String id) {
		return ResourceKey.create(Registries.BIOME, BiomegenCompat.id(id));
	}

	private static ResourceKey<Level> level(String id) {
		return switch (id) {
			case "minecraft:overworld" -> Level.OVERWORLD;
			case "minecraft:the_nether" -> Level.NETHER;
			case "minecraft:the_end" -> Level.END;
			default -> throw new IllegalStateException("Unknown dimension " + id + " in " + Placements.RESOURCE);
		};
	}
}
