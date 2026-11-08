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
		return holders[zoneBiome(field, a, b.wa, qx shl 2, qz shl 2)]
	}

	/** The site biome, or the zone biome its (noise-jittered) radial position falls into: zones are concentric sub-biomes. */
	private fun zoneBiome(field: SiteField, site: SiteField.Site, w: Double, x: Int, z: Int): String {
		val zones = site.spec.zones
		if (zones.isEmpty()) {
			return site.spec.biome
		}
		// Zones are rings by distance from the centre (1 at the centre, 0 at the outer edge), with ragged borders.
		val dx = (x - site.x).toDouble()
		val dz = (z - site.z).toDouble()
		val r = kotlin.math.sqrt(dx * dx + dz * dz) / (site.outer() * site.shape(kotlin.math.atan2(dz, dx)))
		val jw = 1 - r + 0.06 * field.detailNoise(x / 64.0, z / 64.0, 3)
		// A zone starting at 0 or ending at 1 is open on that side (the jitter can push the weight past either end).
		return zones.firstOrNull { (jw >= it.from || it.from <= 0.0) && (jw < it.to || it.to >= 1.0) }?.biome ?: site.spec.biome
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
