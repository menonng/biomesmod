package io.github.menonng.biomesmod.site;

import io.github.menonng.biomesmod.compat.Ids;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Biome source side of the sites: inside a site's inner area the site biome replaces the vanilla lookup. */
public final class SiteBiomes {
	/** Below this depth (about 64 blocks under the surface) the vanilla cave biomes stay. */
	private static final double MAX_DEPTH = 0.5;

	private SiteBiomes() {
	}

	/**
	 * The site biome for quart position (qx, qy, qz), or null to let the biome source decide.
	 * {@code holders} maps biome ids to the source's own holders (site biomes are part of its possible biomes).
	 */
	public static Holder<Biome> override(Map<String, Holder<Biome>> holders, int qx, int qy, int qz, Climate.Sampler sampler) {
		SiteField field = SiteField.forSampler(sampler);
		if (field == null) {
			return null;
		}
		SiteField.Blend b = field.blend((qx << 2) + 2, (qz << 2) + 2);
		if (b.a() == null || b.wa() < b.a().spec().biomeThreshold()) {
			return null;
		}
		double depth = sampler.depth().compute(new DensityFunction.SinglePointContext(qx << 2, qy << 2, qz << 2));
		if (depth > MAX_DEPTH) {
			return null;
		}
		return holders.get(b.a().spec().biome());
	}

	/** Id to holder for every biome the source can produce. */
	public static Map<String, Holder<Biome>> holders(BiomeSource source) {
		Map<String, Holder<Biome>> out = new HashMap<>();
		for (Holder<Biome> h : source.possibleBiomes()) {
			h.unwrapKey().ifPresent(k -> out.put(Ids.name(k), h));
		}
		return out;
	}
}
