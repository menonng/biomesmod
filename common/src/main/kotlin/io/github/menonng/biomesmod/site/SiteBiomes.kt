package io.github.menonng.biomesmod.site

import io.github.menonng.biomesmod.compat.Ids
import net.minecraft.core.Holder
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.BiomeSource
import net.minecraft.world.level.biome.Climate
import net.minecraft.world.level.levelgen.DensityFunction

/** Biome source side of the sites: inside a site's inner area the site biome replaces the vanilla lookup. */
object SiteBiomes {
	/** Below this depth (about 64 blocks under the surface) the vanilla cave biomes stay. */
	private const val MAX_DEPTH = 0.5

	/**
	 * The site biome for quart position (qx, qy, qz), or null to let the biome source decide.
	 * [holders] maps biome ids to the source's own holders (site biomes are part of its possible biomes).
	 */
	fun override(holders: Map<String, Holder<Biome>>, qx: Int, qy: Int, qz: Int, sampler: Climate.Sampler): Holder<Biome>? {
		val field = SiteField.forSampler(sampler) ?: return null
		val b = field.blend((qx shl 2) + 2, (qz shl 2) + 2)
		val a = b.a ?: return null
		if (b.wa < a.spec.biomeThreshold) {
			return null
		}
		val depth = sampler.depth().compute(DensityFunction.SinglePointContext(qx shl 2, qy shl 2, qz shl 2))
		if (depth > MAX_DEPTH) {
			return null
		}
		return holders[a.spec.biome]
	}

	/** Id to holder for every biome the source can produce. */
	fun holders(source: BiomeSource): Map<String, Holder<Biome>> {
		val out = HashMap<String, Holder<Biome>>()
		for (h in source.possibleBiomes()) {
			h.unwrapKey().ifPresent { out[Ids.name(it)] = h }
		}
		return out
	}
}
