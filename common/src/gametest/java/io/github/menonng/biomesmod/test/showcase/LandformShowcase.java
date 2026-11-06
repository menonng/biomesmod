package io.github.menonng.biomesmod.test.showcase;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.menonng.biomesmod.BiomesMod;
import io.github.menonng.biomesmod.compat.LevelCompat;
import io.github.menonng.biomesmod.debug.DebugRender;
import io.github.menonng.biomesmod.terrain.landform.LandformConfig;
import io.github.menonng.biomesmod.terrain.landform.LandformFeatures;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;

/**
 * Dev showcase for biomesmod:landform: every shape decoded from JSON and applied chunk by chunk over a 256x256 area
 * of suitable terrain, then rendered (run/selfcheck/renders/landform_*.png). Use -Ponly=a,b to pick cases.
 */
public final class LandformShowcase {
	private LandformShowcase() {
	}

	private record Case(String name, ResourceKey<Biome> near, String json) {
	}

	private static final String G = "\"spacing\":200,\"chance\":1,\"jitter\":0.2,\"biomes\":[],";
	private static final Case[] CASES = {
			new Case("pillar_forest", Biomes.FOREST, "{\"shape\":\"pillar_forest\"," + G + "\"cluster_radius\":[36,48]}"),
			new Case("karst_towers", Biomes.WARM_OCEAN, "{\"shape\":\"pillar_forest\"," + G + "\"rise_from\":\"sea_level\",\"dome\":1.0,\"notch\":2,"
					+ "\"base_y_range\":[-30,2],\"max_relief\":60,\"pillar_radius\":[5,10],\"pillar_spacing\":20,\"height\":[25,50]}"),
			new Case("butte", Biomes.DESERT, "{\"shape\":\"butte\"," + G + "\"thumbs\":0.8}"),
			new Case("monolith", Biomes.PLAINS, "{\"shape\":\"monolith\"," + G.substring(0, G.length() - 1) + "}"),
			new Case("spires", Biomes.STONY_PEAKS, "{\"shape\":\"monolith\"," + G + "\"count\":3,\"taper\":0.85,\"radius\":[9,13],\"height\":[60,85],"
					+ "\"columns\":0,\"flute\":0.0,\"max_relief\":60}"),
			new Case("cone_sea", Biomes.OCEAN, "{\"shape\":\"cone\"," + G + "\"rise_from\":\"sea_level\",\"snowline\":150,\"crater\":\"dry\"}"),
			new Case("caldera_sea", Biomes.OCEAN, "{\"shape\":\"caldera\"," + G + "\"floor\":\"sea\",\"rise_from\":\"sea_level\"}"),
			new Case("caldera_lake", Biomes.PLAINS, "{\"shape\":\"caldera\"," + G + "\"floor\":\"lake\",\"cone\":1}"),
			new Case("caldera_sand", Biomes.SAVANNA, "{\"shape\":\"caldera\"," + G + "\"floor\":\"sand\",\"cone\":1,"
					+ "\"floor_block\":{\"Name\":\"minecraft:gravel\"}}"),
			new Case("sea_stack", Biomes.STONY_SHORE, "{\"shape\":\"sea_stack\",\"spacing\":64,\"chance\":1,\"biomes\":[]}"),
			new Case("column_field", Biomes.BEACH, "{\"shape\":\"column_field\",\"spacing\":96,\"chance\":1,\"biomes\":[]}"),
			new Case("hot_spring", Biomes.PLAINS, "{\"shape\":\"hot_spring\"," + G.substring(0, G.length() - 1) + "}"),
			new Case("acid_pools", Biomes.DESERT, "{\"shape\":\"hot_spring\"," + G + "\"pools\":14,\"pool_radius\":[3,7],\"band_width\":1.2,\"spread\":[20,34],"
					+ "\"depth\":2,\"chimneys\":0.8,\"bands\":[{\"Name\":\"minecraft:lime_terracotta\"},{\"Name\":\"minecraft:yellow_concrete_powder\"},"
					+ "{\"Name\":\"minecraft:yellow_terracotta\"},{\"Name\":\"minecraft:white_terracotta\"}]}"),
			new Case("cascade", Biomes.JUNGLE, "{\"shape\":\"cascade\"," + G + "\"max_relief\":40}"),
	};

	public static void run(MinecraftServer server) {
		ServerLevel level = server.getLevel(Level.OVERWORLD);
		String only = System.getProperty("biomesmod.showcase.only", "");
		RegistryOps<com.google.gson.JsonElement> ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
		int k = 0;
		for (Case c : CASES) {
			k++;
			if (!only.isEmpty() && !java.util.List.of(only.split(",")).contains(c.name())) {
				continue;
			}
			LandformConfig config = LandformConfig.CODEC.parse(ops, JsonParser.parseString(c.json())).getOrThrow();
			BlockPos center = FieldShowcase.find(level, h -> h.is(c.near()), new BlockPos(-k * 2600, 64, k * 2100));
			if (center == null) {
				BiomesMod.LOGGER.warn("[showcase] {}: no {} found, skipped", c.name(), c.near());
				continue;
			}
			long[] featureNanos = new long[1];
			int chunks = apply(level, config, center, 256, featureNanos);
			double ms = featureNanos[0] / 1e6 / Math.max(1, chunks);
			DebugRender.render(level, center, 256, "landform_" + c.name());
			BiomesMod.LOGGER.info("[showcase] landform_{} at {} | {} chunks, {} ms/chunk", c.name(), center.toShortString(), chunks,
					String.format("%.2f", ms));
		}
	}

	static int apply(ServerLevel level, LandformConfig config, BlockPos center, int size, long[] featureNanos) {
		int half = size / 2;
		int cx0 = (center.getX() - half) >> 4;
		int cz0 = (center.getZ() - half) >> 4;
		int cx1 = (center.getX() + half - 1) >> 4;
		int cz1 = (center.getZ() + half - 1) >> 4;
		for (int cz = cz0 - 1; cz <= cz1 + 1; cz++) {
			for (int cx = cx0 - 1; cx <= cx1 + 1; cx++) {
				level.getChunk(cx, cz);
			}
		}
		RandomSource random = RandomSource.create(7);
		var generator = level.getChunkSource().getGenerator();
		int n = 0;
		long t0 = System.nanoTime();
		for (int cz = cz0; cz <= cz1; cz++) {
			for (int cx = cx0; cx <= cx1; cx++) {
				BlockPos origin = new BlockPos(cx << 4, LevelCompat.minY(level), cz << 4);
				LandformFeatures.LANDFORM.place(new FeaturePlaceContext<>(Optional.empty(), level, generator, random, origin, config));
				n++;
			}
		}
		featureNanos[0] = System.nanoTime() - t0;
		return n;
	}
}
