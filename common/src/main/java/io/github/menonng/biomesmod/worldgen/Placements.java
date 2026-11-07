package io.github.menonng.biomesmod.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import io.github.menonng.biomesmod.site.SiteConfig;

/**
 * Biome placement data written by tools/biomegen/generate.py from specs/ (never edit the JSON by hand):
 * every mod biome with its dimension, the packed TerraBlender overworld regions, nether climate points,
 * End registrations and surface rules. Loaded once from {@value #RESOURCE} on first use.
 */
public final class Placements {
	public static final String RESOURCE = "/biomesmod/placements.json";

	/** A mod biome and the level it generates in ("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"). */
	public record BiomeEntry(String id, String dimension) {
	}

	/** One TerraBlender overworld region: the vanilla layout with each key slot replaced by its value biome. */
	public record OverworldRegion(String name, int weight, Map<String, String> replace) {
	}

	public record NetherPoint(String biome, float temperature, float humidity, float continentalness, float erosion,
			float depth, float weirdness, float offset) {
	}

	/** One TerraBlender nether region: the vanilla nether points (when {@code vanillaPoints}) plus mod points. */
	public record NetherRegion(String name, int weight, boolean vanillaPoints, List<NetherPoint> points) {
	}

	/** {@code type} is highlands, midlands, edge or islands (TerraBlender EndBiomeRegistry). */
	public record EndEntry(String biome, String type, int weight) {
	}

	/** Replaces the top block where {@code noise} (a noise id, sampled in 2D) is in [min, max]. */
	public record Patch(String block, String noise, double min, double max) {
	}

	/**
	 * Surface of one biome. {@code top}, {@code under} and {@code underwater} are block ids or null;
	 * {@code underDepth} 0 means the vanilla variable surface depth.
	 */
	public record Surface(String biome, String top, String under, int underDepth, String underwater, List<Patch> topPatches) {
	}

	private static Placements instance;

	private final List<BiomeEntry> biomes;
	private final List<OverworldRegion> overworldRegions;
	private final List<NetherRegion> netherRegions;
	private final List<EndEntry> end;
	private final List<Surface> overworldSurface;
	private final List<Surface> netherSurface;
	private final SiteConfig sites;

	private Placements(JsonObject root) {
		int format = root.get("format").getAsInt();
		if (format != 1) {
			throw new IllegalStateException("Unsupported " + RESOURCE + " format " + format);
		}
		List<BiomeEntry> biomeList = new ArrayList<>();
		for (JsonElement e : root.getAsJsonArray("biomes")) {
			JsonObject o = e.getAsJsonObject();
			biomeList.add(new BiomeEntry(str(o, "id"), str(o, "dimension")));
		}
		List<OverworldRegion> overworld = new ArrayList<>();
		for (JsonElement e : root.getAsJsonArray("overworld_regions")) {
			JsonObject o = e.getAsJsonObject();
			Map<String, String> replace = new LinkedHashMap<>();
			o.getAsJsonObject("replace").entrySet().forEach(r -> replace.put(r.getKey(), r.getValue().getAsString()));
			overworld.add(new OverworldRegion(str(o, "name"), o.get("weight").getAsInt(), Collections.unmodifiableMap(replace)));
		}
		List<NetherRegion> nether = new ArrayList<>();
		for (JsonElement e : root.getAsJsonArray("nether_regions")) {
			JsonObject o = e.getAsJsonObject();
			List<NetherPoint> points = new ArrayList<>();
			for (JsonElement pe : o.getAsJsonArray("points")) {
				JsonObject p = pe.getAsJsonObject();
				points.add(new NetherPoint(str(p, "biome"), f(p, "temperature"), f(p, "humidity"), f(p, "continentalness"),
						f(p, "erosion"), f(p, "depth"), f(p, "weirdness"), f(p, "offset")));
			}
			nether.add(new NetherRegion(str(o, "name"), o.get("weight").getAsInt(), o.get("vanilla_points").getAsBoolean(), List.copyOf(points)));
		}
		List<EndEntry> endList = new ArrayList<>();
		for (JsonElement e : root.getAsJsonArray("end")) {
			JsonObject o = e.getAsJsonObject();
			endList.add(new EndEntry(str(o, "biome"), str(o, "type"), o.get("weight").getAsInt()));
		}
		JsonObject surface = root.getAsJsonObject("surface");
		this.biomes = List.copyOf(biomeList);
		this.overworldRegions = List.copyOf(overworld);
		this.netherRegions = List.copyOf(nether);
		this.end = List.copyOf(endList);
		this.overworldSurface = surfaces(surface.getAsJsonArray("overworld"));
		this.netherSurface = surfaces(surface.getAsJsonArray("nether"));
		this.sites = SiteConfig.parse(root.getAsJsonObject("sites"));
	}

	/** The placement data shipped in the mod jar. */
	public static synchronized Placements get() {
		if (instance == null) {
			try (InputStream in = Placements.class.getResourceAsStream(RESOURCE)) {
				if (in == null) {
					throw new IllegalStateException(RESOURCE + " is missing; run python -I tools/biomegen/generate.py");
				}
				try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
					instance = new Placements(JsonParser.parseReader(reader).getAsJsonObject());
				}
			} catch (IOException e) {
				throw new IllegalStateException("Cannot read " + RESOURCE, e);
			}
		}
		return instance;
	}

	public List<BiomeEntry> biomes() {
		return biomes;
	}

	public List<OverworldRegion> overworldRegions() {
		return overworldRegions;
	}

	public List<NetherRegion> netherRegions() {
		return netherRegions;
	}

	public List<EndEntry> end() {
		return end;
	}

	public List<Surface> overworldSurface() {
		return overworldSurface;
	}

	public List<Surface> netherSurface() {
		return netherSurface;
	}

	/** Site-placed overworld biomes (see {@link SiteConfig}). */
	public SiteConfig sites() {
		return sites;
	}

	private static List<Surface> surfaces(JsonArray array) {
		List<Surface> out = new ArrayList<>();
		for (JsonElement e : array) {
			JsonObject o = e.getAsJsonObject();
			List<Patch> patches = new ArrayList<>();
			if (o.has("top_patches")) {
				for (JsonElement pe : o.getAsJsonArray("top_patches")) {
					JsonObject p = pe.getAsJsonObject();
					patches.add(new Patch(str(p, "block"), str(p, "noise"), p.get("min").getAsDouble(),
							p.has("max") ? p.get("max").getAsDouble() : Double.MAX_VALUE));
				}
			}
			out.add(new Surface(str(o, "biome"), optStr(o, "top"), optStr(o, "under"),
					o.has("under_depth") ? o.get("under_depth").getAsInt() : 0, optStr(o, "underwater"), List.copyOf(patches)));
		}
		return List.copyOf(out);
	}

	private static String str(JsonObject o, String key) {
		return o.get(key).getAsString();
	}

	private static String optStr(JsonObject o, String key) {
		return o.has(key) ? o.get(key).getAsString() : null;
	}

	private static float f(JsonObject o, String key) {
		return o.has(key) ? o.get(key).getAsFloat() : 0.0F;
	}
}
