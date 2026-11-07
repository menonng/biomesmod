package io.github.menonng.biomesmod.worldgen

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.menonng.biomesmod.site.SiteConfig
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Biome placement data written by tools/biomegen/generate.py from specs/ (never edit the JSON by hand): every mod
 * biome with its dimension, the packed TerraBlender overworld regions, nether climate points, End registrations,
 * surface rules and site layout. Loaded once from [RESOURCE] on first use.
 */
class Placements private constructor(root: JsonObject) {
	/** A mod biome and the level it generates in ("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"). */
	data class BiomeEntry(val id: String, val dimension: String)

	/** One TerraBlender overworld region: the vanilla layout with each key slot replaced by its value biome. */
	data class OverworldRegion(val name: String, val weight: Int, val replace: Map<String, String>)

	data class NetherPoint(
		val biome: String, val temperature: Float, val humidity: Float, val continentalness: Float, val erosion: Float,
		val depth: Float, val weirdness: Float, val offset: Float,
	)

	/** One TerraBlender nether region: the vanilla nether points (when [vanillaPoints]) plus mod points. */
	data class NetherRegion(val name: String, val weight: Int, val vanillaPoints: Boolean, val points: List<NetherPoint>)

	/** [type] is highlands, midlands, edge or islands (TerraBlender EndBiomeRegistry). */
	data class EndEntry(val biome: String, val type: String, val weight: Int)

	/** Replaces the top block where [noise] (a noise id, sampled in 2D) is in [min, max]. */
	data class Patch(val block: String, val noise: String, val min: Double, val max: Double)

	/**
	 * Surface of one biome. [top], [under] and [underwater] are block ids or null; [underDepth] 0 means the vanilla
	 * variable surface depth.
	 */
	data class Surface(
		val biome: String, val top: String?, val under: String?, val underDepth: Int, val underwater: String?,
		val topPatches: List<Patch>,
	)

	val biomes: List<BiomeEntry>
	val overworldRegions: List<OverworldRegion>
	val netherRegions: List<NetherRegion>
	val end: List<EndEntry>
	val overworldSurface: List<Surface>
	val netherSurface: List<Surface>

	/** Site-placed overworld biomes (see [SiteConfig]). */
	val sites: SiteConfig

	init {
		val format = root.get("format").asInt
		check(format == 1) { "Unsupported $RESOURCE format $format" }
		biomes = root.getAsJsonArray("biomes").map { val o = it.asJsonObject; BiomeEntry(str(o, "id"), str(o, "dimension")) }
		overworldRegions = root.getAsJsonArray("overworld_regions").map { e ->
			val o = e.asJsonObject
			val replace = LinkedHashMap<String, String>()
			o.getAsJsonObject("replace").entrySet().forEach { replace[it.key] = it.value.asString }
			OverworldRegion(str(o, "name"), o.get("weight").asInt, replace)
		}
		netherRegions = root.getAsJsonArray("nether_regions").map { e ->
			val o = e.asJsonObject
			val points = o.getAsJsonArray("points").map { pe ->
				val p = pe.asJsonObject
				NetherPoint(str(p, "biome"), f(p, "temperature"), f(p, "humidity"), f(p, "continentalness"), f(p, "erosion"),
					f(p, "depth"), f(p, "weirdness"), f(p, "offset"))
			}
			NetherRegion(str(o, "name"), o.get("weight").asInt, o.get("vanilla_points").asBoolean, points)
		}
		end = root.getAsJsonArray("end").map { val o = it.asJsonObject; EndEntry(str(o, "biome"), str(o, "type"), o.get("weight").asInt) }
		val surface = root.getAsJsonObject("surface")
		overworldSurface = surfaces(surface.getAsJsonArray("overworld"))
		netherSurface = surfaces(surface.getAsJsonArray("nether"))
		sites = SiteConfig.parse(root.getAsJsonObject("sites"))
	}

	companion object {
		const val RESOURCE = "/biomesmod/placements.json"

		private var instance: Placements? = null

		/** The placement data shipped in the mod jar. */
		@JvmStatic
		@Synchronized
		fun get(): Placements {
			instance?.let { return it }
			try {
				val stream = Placements::class.java.getResourceAsStream(RESOURCE)
					?: throw IllegalStateException("$RESOURCE is missing; run python -I tools/biomegen/generate.py")
				InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
					return Placements(JsonParser.parseReader(reader).asJsonObject).also { instance = it }
				}
			} catch (e: IOException) {
				throw IllegalStateException("Cannot read $RESOURCE", e)
			}
		}

		private fun surfaces(array: JsonArray): List<Surface> = array.map { e ->
			val o = e.asJsonObject
			val patches = if (!o.has("top_patches")) listOf() else o.getAsJsonArray("top_patches").map { pe ->
				val p = pe.asJsonObject
				Patch(str(p, "block"), str(p, "noise"), p.get("min").asDouble, if (p.has("max")) p.get("max").asDouble else Double.MAX_VALUE)
			}
			Surface(str(o, "biome"), optStr(o, "top"), optStr(o, "under"), if (o.has("under_depth")) o.get("under_depth").asInt else 0,
				optStr(o, "underwater"), patches)
		}

		private fun str(o: JsonObject, key: String): String = o.get(key).asString

		private fun optStr(o: JsonObject, key: String): String? = if (o.has(key)) o.get(key).asString else null

		private fun f(o: JsonObject, key: String): Float = if (o.has(key)) o.get(key).asFloat else 0.0f
	}
}
