package io.github.menonng.biomesmod.site;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Site placement data from placements.json ("sites"), written by tools/biomegen from each biome spec's
 * {@code placement.site}. A site is one large, roughly round area of a single mod biome: the biome source
 * returns that biome inside it and the terrain is raised, lowered or flattened towards the place's real
 * elevation, fading out over a ramp so it joins the surrounding land.
 */
public record SiteConfig(int cell, double jitter, double chance, double warp, List<Spec> specs) {

	/** How a site reshapes the vanilla terrain. */
	public enum Terrain {
		/** Biome only, the vanilla terrain stays (oceans, coasts). */
		KEEP,
		/** Flat land at {@code targetY}: offset to the target, high factor, no jaggedness (salt flats, ice plateaus). */
		PLATEAU,
		/** The land is moved to {@code targetY} and keeps {@code relief} of its own hills (high steppe, uplands). */
		LIFT,
		/** Like LIFT, but vanilla mountains above the target stay (massifs, alpine valleys). */
		MOUNTAIN,
		/** Flat low land at {@code targetY} (wetlands, floodplains, depressions). */
		BASIN
	}

	/**
	 * One site-placed biome. Climate ranges are in vanilla parameter space (temperature, humidity,
	 * continentalness and erosion at the site centre); {@code weight} is its share among the biomes that accept
	 * a centre. {@code core} is the radius of the full-strength area, {@code ramp} the width of the fade.
	 */
	public record Spec(String biome, double weight, double[] temperature, double[] humidity, double[] continentalness,
			double[] erosion, double coreMin, double coreMax, double ramp, Terrain terrain, int targetY, double relief,
			double biomeThreshold) {

		boolean accepts(double t, double h, double c, double e) {
			return in(temperature, t) && in(humidity, h) && in(continentalness, c) && in(erosion, e);
		}

		private static boolean in(double[] r, double v) {
			return v >= r[0] && v <= r[1];
		}
	}

	public static final SiteConfig EMPTY = new SiteConfig(2048, 0.8, 0.0, 0.0, List.of());

	public double maxOuter() {
		double m = 0;
		for (Spec s : specs) {
			m = Math.max(m, s.coreMax * 1.3 + s.ramp);
		}
		return m + warp;
	}

	public static SiteConfig parse(JsonObject o) {
		if (o == null) {
			return EMPTY;
		}
		JsonObject grid = o.getAsJsonObject("grid");
		List<Spec> specs = new ArrayList<>();
		for (JsonElement e : o.getAsJsonArray("list")) {
			JsonObject s = e.getAsJsonObject();
			specs.add(new Spec(s.get("biome").getAsString(), s.get("weight").getAsDouble(), range(s, "temperature"),
					range(s, "humidity"), range(s, "continentalness"), range(s, "erosion"), range(s, "core")[0],
					range(s, "core")[1], s.get("ramp").getAsDouble(), Terrain.valueOf(s.get("terrain").getAsString().toUpperCase()),
					s.get("target_y").getAsInt(), s.get("relief").getAsDouble(), s.get("biome_threshold").getAsDouble()));
		}
		return new SiteConfig(grid.get("cell").getAsInt(), grid.get("jitter").getAsDouble(), grid.get("chance").getAsDouble(),
				grid.get("warp").getAsDouble(), List.copyOf(specs));
	}

	private static double[] range(JsonObject s, String key) {
		if (!s.has(key)) {
			return new double[] {-10, 10};
		}
		JsonArray a = s.getAsJsonArray(key);
		return new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble()};
	}
}
