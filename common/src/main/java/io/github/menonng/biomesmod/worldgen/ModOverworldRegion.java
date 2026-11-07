package io.github.menonng.biomesmod.worldgen;

import com.mojang.datafixers.util.Pair;
import io.github.menonng.biomesmod.ModBiomes;
import io.github.menonng.biomesmod.compat.BiomegenCompat;
import java.util.LinkedHashMap;
import java.util.List;
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
	private final List<String> carriers;

	/**
	 * {@code carriers}: site-placed biomes to add at an unreachable climate point, so they are part of the biome
	 * source's possible biomes (feature decoration, locate) while the climate lookup never returns them; the site
	 * mixin places them.
	 */
	public ModOverworldRegion(Placements.OverworldRegion data, List<String> carriers) {
		super(BiomegenCompat.id(data.name()), RegionType.OVERWORLD, data.weight());
		data.replace().forEach((vanilla, mod) -> replacements.put(ModBiomes.key(vanilla), ModBiomes.key(mod)));
		this.carriers = carriers;
	}

	@Override
	public void addBiomes(Registry<Biome> registry, Consumer<Pair<Climate.ParameterPoint, ResourceKey<Biome>>> mapper) {
		addModifiedVanillaOverworldBiomes(mapper, builder -> replacements.forEach(builder::replaceBiome));
		Climate.Parameter far = Climate.Parameter.point(9.0F);
		for (String id : carriers) {
			mapper.accept(Pair.of(Climate.parameters(far, far, far, far, far, far, 0.0F), ModBiomes.key(id)));
		}
	}
}
