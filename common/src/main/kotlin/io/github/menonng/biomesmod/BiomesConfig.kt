package io.github.menonng.biomesmod

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
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

	init {
		val path = FabricLoader.getInstance().configDir.resolve("biomesmod.json")
		val root = try {
			if (Files.exists(path)) JsonParser.parseString(Files.readString(path)).asJsonObject else JsonObject()
		} catch (e: Exception) {
			BiomesMod.LOGGER.warn("config/biomesmod.json is not valid JSON, using the defaults", e)
			JsonObject()
		}
		val e = root.obj("elevation")
		val d = Elevation()
		elevation = Elevation(e.num("sea_level_y", d.seaLevelY), e.num("metres_per_block", d.metresPerBlock), e.num("highland_metres", d.highlandMetres),
			e.num("highland_min_y", d.highlandMinY), e.num("highland_metres_per_block", d.highlandMetresPerBlock), e.num("max_target_y", d.maxTargetY))
		val s = root.obj("sites")
		val ds = Sites()
		val w = s.obj("weights")
		sites = Sites(s.num("cell", ds.cell.toDouble()).toInt(), s.num("chance", ds.chance), s.num("size_scale", ds.sizeScale),
			s.num("large_biomes_scale", ds.largeBiomesScale), w.keySet().associateWith { w.get(it).asDouble })
		ridgeScale = root.obj("mountains").num("ridge_scale", 1.0)
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
		try {
			Files.createDirectories(path.parent)
			Files.writeString(path, GsonBuilder().setPrettyPrinting().create().toJson(o) + "\n")
		} catch (ex: Exception) {
			BiomesMod.LOGGER.warn("Cannot write {}", path, ex)
		}
	}

	private fun JsonObject.obj(key: String): JsonObject = if (has(key) && get(key).isJsonObject) getAsJsonObject(key) else JsonObject()

	private fun JsonObject.num(key: String, def: Double): Double =
		if (has(key) && get(key).isJsonPrimitive && get(key).asJsonPrimitive.isNumber) get(key).asDouble else def
}
