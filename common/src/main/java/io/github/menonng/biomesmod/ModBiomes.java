package io.github.menonng.biomesmod;

import io.github.menonng.biomesmod.compat.Ids;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * All mod biomes and the dimension each one generates in.
 * Biome definitions are data-driven (data/biomesmod/worldgen/biome/*.json, one set per version).
 * Placement for all dimensions is registered in {@link io.github.menonng.biomesmod.worldgen.TerraBlenderEntry}.
 */
public final class ModBiomes {
	public record Entry(ResourceKey<Biome> key, ResourceKey<Level> dimension) {
	}

	/** Overworld. Motif: Jeju Gotjawal, a humid evergreen forest over broken lava rock. */
	public static final ResourceKey<Biome> GOTJAWAL = key("gotjawal");
	/** Nether. Motif: Danakil Depression, salt crust, sulfur haze and lava pools. */
	public static final ResourceKey<Biome> DANAKIL_FLATS = key("danakil_flats");
	/** End. Dense chorus forest in the outer highlands, violet fog and portal motes. */
	public static final ResourceKey<Biome> CHORUS_THICKET = key("chorus_thicket");

	public static final List<Entry> ALL = List.of(
			new Entry(GOTJAWAL, Level.OVERWORLD),
			new Entry(DANAKIL_FLATS, Level.NETHER),
			new Entry(CHORUS_THICKET, Level.END));

	private ModBiomes() {
	}

	private static ResourceKey<Biome> key(String path) {
		return ResourceKey.create(Registries.BIOME, Ids.id(path));
	}

}
