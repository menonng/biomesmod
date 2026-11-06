package io.github.menonng.biomesmod.terrain.landform;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;

/** {@code "biomes": ["biomesmod:zhangjiajie", "#minecraft:is_ocean"]}; empty = everywhere. */
public record BiomeFilter(List<Either<TagKey<Biome>, ResourceKey<Biome>>> entries) {
	private static final Codec<Either<TagKey<Biome>, ResourceKey<Biome>>> ENTRY =
			Codec.either(TagKey.hashedCodec(Registries.BIOME), ResourceKey.codec(Registries.BIOME));
	public static final Codec<BiomeFilter> CODEC = ENTRY.listOf().xmap(BiomeFilter::new, BiomeFilter::entries);
	public static final BiomeFilter ANY = new BiomeFilter(List.of());

	public boolean everywhere() {
		return entries.isEmpty();
	}

	public boolean test(Holder<Biome> biome) {
		if (entries.isEmpty()) {
			return true;
		}
		for (Either<TagKey<Biome>, ResourceKey<Biome>> e : entries) {
			boolean hit = e.map(biome::is, biome::is);
			if (hit) {
				return true;
			}
		}
		return false;
	}
}
