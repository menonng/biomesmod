package io.github.menonng.biomesmod.terrain.field;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryCodecs;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.Biome;

/**
 * Settings shared by every terrain_shaper mode (inlined next to "mode" in the JSON).
 *
 * @param biomes     biomes (ids, lists or a #tag) the shaping applies in; empty = everywhere (showcases only)
 * @param edgeBlend  blocks over which the reshaping fades in from the biome edge
 * @param edgeNoise  0..1, how much the fade band width wanders (as a fraction of edge_blend)
 * @param seed       salt mixed into the world seed, to decorrelate two biomes using the same mode
 * @param palette    blocks for the new surface; see {@link FieldPalette}
 * @param sealDepth  air/fluid pockets (caves, carver ravines) within this many blocks under the new surface are filled
 */
public record Common(HolderSet<Biome> biomes, int edgeBlend, double edgeNoise, int seed, Optional<FieldPalette> palette, int sealDepth) {
	public static final MapCodec<Common> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			RegistryCodecs.homogeneousList(Registries.BIOME).optionalFieldOf("biomes", emptyBiomes()).forGetter(Common::biomes),
			Codec.intRange(0, 256).optionalFieldOf("edge_blend", 32).forGetter(Common::edgeBlend),
			Codec.doubleRange(0, 1).optionalFieldOf("edge_noise", 0.35).forGetter(Common::edgeNoise),
			Codec.INT.optionalFieldOf("seed", 0).forGetter(Common::seed),
			FieldPalette.CODEC.optionalFieldOf("palette").forGetter(Common::palette),
			Codec.intRange(0, 32).optionalFieldOf("seal_depth", 6).forGetter(Common::sealDepth)
	).apply(i, Common::new));

	public static HolderSet<Biome> emptyBiomes() {
		return HolderSet.direct(List.<Holder<Biome>>of());
	}

	/** True only for an explicitly empty list (an empty #tag still means "nowhere"). */
	public boolean everywhere() {
		return biomes.unwrapKey().isEmpty() && biomes.size() == 0;
	}
}
