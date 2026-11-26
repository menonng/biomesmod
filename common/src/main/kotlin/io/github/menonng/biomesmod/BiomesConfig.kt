package io.github.menonng.biomesmod

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.menonng.biomesmod.platform.Loader
import java.nio.file.Files
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Server-side world generation settings in `config/biomesmod.json`, written with the defaults on first start. They shape
 * new terrain only: change them before generating a world (chunks that exist keep their old shape).
 *
 * - `elevation`: how a place's real elevation becomes a block height. Below `highland_metres` it is
 *   `sea_level_y + metres / metres_per_block`; from `highland_metres` up, high ground starts at `highland_min_y` and
 *   climbs `1 / highland_metres_per_block` blocks per metre, capped at `max_target_y`.
 * - `sites`: grid cell, chance per cell, size factor of every site (and the extra factor on Large Biomes), and a weight
 *   factor per biome (`"socotra": 0.5` halves how often Socotra is picked).
 * - `mountains.ridge_scale`: height factor of the ridged mountain peaks on mountain sites (0 turns them off).
 * - `uyuni.mirror`: what the flooded part of Salar de Uyuni is made of. `block` (default): the `salt_mirror` block, a
 *   dead-calm solid film that PBR shader packs mirror the sky in; `water`: a one-block film of vanilla water.
 */
object BiomesConfig {
	class Elevation(
		val seaLevelY: Double = 63.0,
		val metresPerBlock: Double = 25.6,
		val highlandMetres: Double = 2000.0,
		val highlandMinY: Double = 190.0,
		val highlandMetresPerBlock: Double = 40.0,
		val maxTargetY: Double = 300.0,
	)

	class Sites(
		val cell: Int = 2048,
		val chance: Double = 0.85,
		val sizeScale: Double = 1.0,
		val largeBiomesScale: Double = 2.0,
		val weights: Map<String, Double> = mapOf(),
	)

	val elevation: Elevation
	val sites: Sites
	val ridgeScale: Double
	val mirrorBlock: Boolean

	init {
		val path = Loader.hooks.configDir.resolve("biomesmod.json")
		val root = try {
			if (Files.exists(path)) JsonParser.parseString(Files.readString(path)).asJsonObject else JsonObject()
		} catch (e: Exception) {
			BiomesMod.LOGGER.warn("config/biomesmod.json is not valid JSON, using the defaults", e)
			JsonObject()
		}
		// Every number is held to a range the generator can work with: a typo must not crash world creation or hang it.
		val e = root.obj("elevation")
		val d = Elevation()
		elevation = Elevation(e.num("sea_level_y", d.seaLevelY, 0.0, 255.0), e.num("metres_per_block", d.metresPerBlock, 1.0, 1000.0),
			e.num("highland_metres", d.highlandMetres, 0.0, 9000.0), e.num("highland_min_y", d.highlandMinY, 0.0, 300.0),
			e.num("highland_metres_per_block", d.highlandMetresPerBlock, 1.0, 1000.0), e.num("max_target_y", d.maxTargetY, 64.0, 300.0))
		val s = root.obj("sites")
		val ds = Sites()
		val w = s.obj("weights")
		sites = Sites(s.num("cell", ds.cell.toDouble(), 512.0, 16384.0).toInt(), s.num("chance", ds.chance, 0.0, 1.0),
			s.num("size_scale", ds.sizeScale, 0.25, 4.0), s.num("large_biomes_scale", ds.largeBiomesScale, 0.25, 4.0),
			w.keySet().filter { it != "_help" }.associateWith { w.num(it, 1.0, 0.0, 100.0) })
		ridgeScale = root.obj("mountains").num("ridge_scale", 1.0, 0.0, 3.0)
		val um = root.obj("uyuni")
		mirrorBlock = !(um.has("mirror") && um.get("mirror").isJsonPrimitive && um.get("mirror").asString == "water")
		write(path)
	}

	/** Block height of a place at [metres] real elevation. */
	fun targetY(metres: Double): Int {
		val e = elevation
		val y = if (metres < e.highlandMetres) e.seaLevelY + metres / e.metresPerBlock
		else max(e.highlandMinY, e.seaLevelY + e.highlandMetres / e.metresPerBlock) + (metres - e.highlandMetres) / e.highlandMetresPerBlock
		return min(e.maxTargetY, max(0.0, y)).roundToInt()
	}

	/** Weight factor for a biome id (with or without the biomesmod: namespace). */
	fun weight(biome: String): Double = sites.weights[biome.substringAfter(':')] ?: sites.weights[biome] ?: 1.0

	private fun write(path: java.nio.file.Path) {
		val o = JsonObject()
		o.addProperty("_help", "World generation settings of Biomes Mod. They only shape new chunks. See docs/engine-sites.md.")
		o.add("elevation", JsonObject().apply {
			addProperty("_help", "Real elevation to block height: below highland_metres y = sea_level_y + metres / metres_per_block; " +
				"from highland_metres up, high ground starts at highland_min_y and rises 1 block per highland_metres_per_block metres.")
			addProperty("sea_level_y", elevation.seaLevelY)
			addProperty("metres_per_block", elevation.metresPerBlock)
			addProperty("highland_metres", elevation.highlandMetres)
			addProperty("highland_min_y", elevation.highlandMinY)
			addProperty("highland_metres_per_block", elevation.highlandMetresPerBlock)
			addProperty("max_target_y", elevation.maxTargetY)
		})
		o.add("sites", JsonObject().apply {
			addProperty("_help", "cell: grid size in blocks (one site at most per cell); chance: share of cells holding a site; size_scale: " +
				"size factor of every site; large_biomes_scale: extra factor on the Large Biomes preset; weights: factor per biome id.")
			addProperty("cell", sites.cell)
			addProperty("chance", sites.chance)
			addProperty("size_scale", sites.sizeScale)
			addProperty("large_biomes_scale", sites.largeBiomesScale)
			add("weights", JsonObject().apply { sites.weights.forEach { (k, v) -> addProperty(k, v) } })
		})
		o.add("mountains", JsonObject().apply {
			addProperty("_help", "ridge_scale: height factor of the ridged peaks on mountain sites (0 = off).")
			addProperty("ridge_scale", ridgeScale)
		})
		o.add("uyuni", JsonObject().apply {
			addProperty("_help", "mirror: 'block' paves the flooded salt flat with the salt_mirror block (reflects under PBR shader packs with " +
				"specular reflections on, plain texture otherwise); 'water' uses a one-block film of vanilla water instead.")
			addProperty("mirror", if (mirrorBlock) "block" else "water")
		})
		try {
			Files.createDirectories(path.parent)
			Files.writeString(path, GsonBuilder().setPrettyPrinting().create().toJson(o) + "\n")
		} catch (ex: Exception) {
			BiomesMod.LOGGER.warn("Cannot write {}", path, ex)
		}
	}

	private fun JsonObject.obj(key: String): JsonObject = if (has(key) && get(key).isJsonObject) getAsJsonObject(key) else JsonObject()

	/** The number at [key], or [def] when it is missing or not a number; held to [lo]..[hi], with a warning when it was outside. */
	private fun JsonObject.num(key: String, def: Double, lo: Double, hi: Double): Double {
		if (!has(key)) {
			return def
		}
		val el = get(key)
		val v = if (el.isJsonPrimitive && el.asJsonPrimitive.isNumber) el.asDouble else Double.NaN
		if (v.isNaN() || v.isInfinite()) {
			BiomesMod.LOGGER.warn("config/biomesmod.json: {} is not a number, using {}", key, def)
			return def
		}
		if (v < lo || v > hi) {
			BiomesMod.LOGGER.warn("config/biomesmod.json: {} = {} is outside {}..{}, using the nearest allowed value", key, v, lo, hi)
		}
		return v.coerceIn(lo, hi)
	}
}
