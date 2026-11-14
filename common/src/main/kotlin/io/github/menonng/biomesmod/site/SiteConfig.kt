package io.github.menonng.biomesmod.site

import com.google.gson.JsonObject
import io.github.menonng.biomesmod.BiomesConfig

/**
 * Site placement data from placements.json ("sites"), written by tools/biomegen from each biome spec's
 * `placement.site`. A site is one large, roughly round area of a single mod biome: the biome source returns that
 * biome inside it and the terrain is raised, lowered or flattened towards the place's real elevation, fading out
 * over a ramp so it joins the surrounding land.
 */
data class SiteConfig(val cell: Int, val jitter: Double, val chance: Double, val warp: Double, val specs: List<Spec>) {

	/** How a site reshapes the vanilla terrain. */
	enum class Terrain {
		/** Biome only, the vanilla terrain stays (oceans, coasts). */
		KEEP,

		/** Flat land at `targetY`: offset to the target, high factor, no jaggedness (salt flats, ice plateaus). */
		PLATEAU,

		/** The land is moved to `targetY` and keeps `relief` of its own hills (high steppe, uplands). */
		LIFT,

		/** Like LIFT, but vanilla mountains above the target stay (massifs, alpine valleys). */
		MOUNTAIN,

		/** Flat low land at `targetY` (wetlands, floodplains, depressions). */
		BASIN,
	}

	/** A sub-biome of a site: used where the site weight (lightly noise-jittered) lies in [from, to). */
	data class Zone(val biome: String, val from: Double, val to: Double)

	/** Fjord channels cut from the site centre towards the sea: [count] channels, half [width] blocks, [depth] below sea. */
	data class Channels(val count: Int, val width: Double, val depth: Double)

	/**
	 * One site-placed biome. Climate ranges are in vanilla parameter space (temperature, humidity, continentalness
	 * and erosion at the site centre); [weight] is its share among the biomes that accept a centre. [coreMin] to
	 * [coreMax] is the radius of the full-strength area, [ramp] the width of the fade. [targetY] comes from the real
	 * elevation through the config (`BiomesConfig.targetY`) unless the spec fixes it. [ridges] adds ridged peaks of up
	 * to that many blocks (mountain sites), [zones] are sub-biomes towards the edge, [channels] fjords.
	 */
	class Spec(
		val biome: String,
		val weight: Double,
		val temperature: DoubleArray,
		val humidity: DoubleArray,
		val continentalness: DoubleArray,
		val erosion: DoubleArray,
		val coreMin: Double,
		val coreMax: Double,
		val ramp: Double,
		val terrain: Terrain,
		val targetY: Int,
		val relief: Double,
		val biomeThreshold: Double,
		val ridges: Double = 0.0,
		val zones: List<Zone> = listOf(),
		val channels: Channels? = null,
		/** The site must stand alone in the sea: it is only placed where open ocean lies all the way round it. */
		val island: Boolean = false,
	) {
		fun accepts(t: Double, h: Double, c: Double, e: Double): Boolean =
			inRange(temperature, t) && inRange(humidity, h) && inRange(continentalness, c) && inRange(erosion, e)

		private fun inRange(r: DoubleArray, v: Double): Boolean = v >= r[0] && v <= r[1]

		fun scaled(f: Double): Spec = Spec(biome, weight, temperature, humidity, continentalness, erosion, coreMin * f, coreMax * f, ramp * f,
			terrain, targetY, relief, biomeThreshold, ridges, zones, channels?.let { Channels(it.count, it.width * f, it.depth) }, island)
	}

	/** The largest distance from a centre at which any site still has weight, warp included. */
	fun maxOuter(): Double = (specs.maxOfOrNull { it.coreMax * 1.3 + it.ramp } ?: 0.0) + warp

	/** The same layout scaled by [factor] (cell, sizes, ramps and warp), for presets with stretched climate. */
	fun scaled(factor: Double): SiteConfig =
		if (factor == 1.0) this else SiteConfig((cell * factor).toInt(), jitter, chance, warp * factor, specs.map { it.scaled(factor) })

	/** Every biome a site can produce (the site biomes and their zones). */
	fun allBiomes(): List<String> = specs.flatMap { listOf(it.biome) + it.zones.map { z -> z.biome } }.distinct()

	companion object {
		@JvmField
		val EMPTY = SiteConfig(2048, 0.8, 0.0, 0.0, listOf())

		fun parse(o: JsonObject?): SiteConfig {
			if (o == null) {
				return EMPTY
			}
			val grid = o.getAsJsonObject("grid")
			val cfg = BiomesConfig.sites
			val specs = o.getAsJsonArray("list").map { e ->
				val s = e.asJsonObject
				val core = range(s, "core")
				// A real elevation goes through the config mapping; a fixed target_y (no elevation) stays as written.
				val target = if (s.has("elevation_m") && !s.has("fixed_y")) BiomesConfig.targetY(s.get("elevation_m").asDouble) else s.get("target_y").asInt
				val zones = if (!s.has("zones")) listOf() else s.getAsJsonArray("zones").map { z ->
					val zo = z.asJsonObject
					Zone(zo.get("biome").asString, zo.get("from").asDouble, zo.get("to").asDouble)
				}
				val channels = if (!s.has("channels")) null else s.getAsJsonObject("channels").let {
					Channels(it.get("count").asInt, it.get("width").asDouble, it.get("depth").asDouble)
				}
				val biome = s.get("biome").asString
				Spec(biome, s.get("weight").asDouble * BiomesConfig.weight(biome), range(s, "temperature"), range(s, "humidity"),
					range(s, "continentalness"), range(s, "erosion"), core[0], core[1], s.get("ramp").asDouble,
					Terrain.valueOf(s.get("terrain").asString.uppercase()), target, s.get("relief").asDouble, s.get("biome_threshold").asDouble,
					if (s.has("ridges")) s.get("ridges").asDouble * BiomesConfig.ridgeScale else 0.0, zones, channels,
					s.has("island") && s.get("island").asBoolean).scaled(cfg.sizeScale)
			}.filter { it.weight > 0 }
			return SiteConfig(cfg.cell, grid.get("jitter").asDouble, cfg.chance, grid.get("warp").asDouble * cfg.sizeScale, specs)
		}

		private fun range(s: JsonObject, key: String): DoubleArray {
			if (!s.has(key)) {
				return doubleArrayOf(-10.0, 10.0)
			}
			val a = s.getAsJsonArray(key)
			return doubleArrayOf(a.get(0).asDouble, a.get(1).asDouble)
		}
	}
}
