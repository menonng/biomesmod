package io.github.menonng.biomesmod.site

import com.google.gson.JsonObject

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

	/**
	 * One site-placed biome. Climate ranges are in vanilla parameter space (temperature, humidity, continentalness
	 * and erosion at the site centre); [weight] is its share among the biomes that accept a centre. [coreMin] to
	 * [coreMax] is the radius of the full-strength area, [ramp] the width of the fade.
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
	) {
		fun accepts(t: Double, h: Double, c: Double, e: Double): Boolean =
			inRange(temperature, t) && inRange(humidity, h) && inRange(continentalness, c) && inRange(erosion, e)

		private fun inRange(r: DoubleArray, v: Double): Boolean = v >= r[0] && v <= r[1]
	}

	/** The largest distance from a centre at which any site still has weight, warp included. */
	fun maxOuter(): Double = (specs.maxOfOrNull { it.coreMax * 1.3 + it.ramp } ?: 0.0) + warp

	/** The same layout scaled by [factor] (cell, sizes, ramps and warp), for presets with stretched climate. */
	fun scaled(factor: Double): SiteConfig = if (factor == 1.0) this else SiteConfig(
		(cell * factor).toInt(), jitter, chance, warp * factor,
		specs.map {
			Spec(it.biome, it.weight, it.temperature, it.humidity, it.continentalness, it.erosion, it.coreMin * factor,
				it.coreMax * factor, it.ramp * factor, it.terrain, it.targetY, it.relief, it.biomeThreshold)
		})

	companion object {
		@JvmField
		val EMPTY = SiteConfig(2048, 0.8, 0.0, 0.0, listOf())

		fun parse(o: JsonObject?): SiteConfig {
			if (o == null) {
				return EMPTY
			}
			val grid = o.getAsJsonObject("grid")
			val specs = o.getAsJsonArray("list").map { e ->
				val s = e.asJsonObject
				val core = range(s, "core")
				Spec(s.get("biome").asString, s.get("weight").asDouble, range(s, "temperature"), range(s, "humidity"),
					range(s, "continentalness"), range(s, "erosion"), core[0], core[1], s.get("ramp").asDouble,
					Terrain.valueOf(s.get("terrain").asString.uppercase()), s.get("target_y").asInt, s.get("relief").asDouble,
					s.get("biome_threshold").asDouble)
			}
			return SiteConfig(grid.get("cell").asInt, grid.get("jitter").asDouble, grid.get("chance").asDouble,
				grid.get("warp").asDouble, specs)
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
