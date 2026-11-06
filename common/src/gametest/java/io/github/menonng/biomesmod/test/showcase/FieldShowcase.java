package io.github.menonng.biomesmod.test.showcase;

import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.JsonOps;
import io.github.menonng.biomesmod.BiomesMod;
import io.github.menonng.biomesmod.compat.LevelCompat;
import io.github.menonng.biomesmod.debug.DebugRender;
import io.github.menonng.biomesmod.terrain.field.FieldStats;
import io.github.menonng.biomesmod.terrain.field.ShaperMode;
import io.github.menonng.biomesmod.terrain.field.TerrainFeatures;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
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
 * Dev showcase for biomesmod:terrain_shaper: every mode, decoded from JSON through the real codec, applied over a
 * 256x256 area on suitable vanilla terrain, then rendered (run/selfcheck/renders/field_*.png).
 * {@code ./gradlew :26.2:runSelfCheck -Pshowcase=field -Prender=true}; add -Dbiomesmod.showcase.only=<name> to run one.
 */
public final class FieldShowcase {
	private FieldShowcase() {
	}

	private record Case(String name, ResourceKey<Biome> near, String json) {
	}

	private static final Case[] CASES = {
			new Case("flatten", Biomes.DESERT, "{\"mode\":\"flatten\",\"biomes\":[],\"crust\":0.7,\"water_coverage\":0.3,\"relief\":0.4}"),
			new Case("depression", Biomes.PLAINS, "{\"mode\":\"depression\",\"biomes\":[\"minecraft:plains\"],\"edge_blend\":10,\"depth\":7,\"ditch_spacing\":28}"),
			new Case("dunes_lagoons", Biomes.BEACH, "{\"mode\":\"dunes\",\"biomes\":[],\"height\":9,\"wavelength\":36,\"lagoon_depth\":3,"
					+ "\"palette\":{\"top\":[{\"block\":{\"Name\":\"minecraft:white_concrete_powder\"},\"depth\":2},{\"block\":{\"Name\":\"minecraft:sand\"},\"depth\":3}]}}"),
			new Case("dunes_star", Biomes.DESERT, "{\"mode\":\"dunes\",\"biomes\":[],\"style\":\"star\",\"height\":38,\"wavelength\":95,\"pan_coverage\":0.18,"
					+ "\"palette\":{\"top\":[{\"block\":{\"Name\":\"minecraft:red_sand\"},\"depth\":4}]}}"),
			new Case("terraces_pamukkale", Biomes.MEADOW, "{\"mode\":\"terraces\",\"biomes\":[],\"step\":2,\"pool_depth\":1,\"tilt\":30,\"lobe\":1.4}"),
			new Case("terraces_plitvice", Biomes.FOREST, "{\"mode\":\"terraces\",\"biomes\":[],\"step\":7,\"pool_depth\":5,\"rim_width\":2.5,\"falls\":0.5,\"tilt\":45,\"pool_coverage\":0.4,"
					+ "\"palette\":{\"top\":[{\"block\":{\"Name\":\"minecraft:moss_block\"},\"depth\":1},{\"block\":{\"Name\":\"minecraft:calcite\"},\"depth\":2}],"
					+ "\"underwater\":[{\"block\":{\"Name\":\"minecraft:calcite\"},\"depth\":2}]}}"),
			new Case("canyon", Biomes.BADLANDS, "{\"mode\":\"canyon\",\"biomes\":[],\"spacing\":200,\"width\":120,\"depth\":60,\"side_spacing\":110}"),
			new Case("u_valley", Biomes.MEADOW, "{\"mode\":\"u_valley\",\"biomes\":[],\"spacing\":220,\"width\":100,\"floor_width\":40,\"shoulder\":55,\"falls\":4}"),
			new Case("fjord", Biomes.STONY_SHORE, "{\"mode\":\"u_valley\",\"biomes\":[],\"spacing\":200,\"width\":90,\"floor_width\":34,\"shoulder\":60,\"floor_depth\":14,"
					+ "\"flood_to_sea\":true,\"falls\":3}"),
			new Case("ice_sheet", Biomes.SNOWY_PLAINS, "{\"mode\":\"ice_sheet\",\"biomes\":[],\"thickness\":16,\"dome\":10}"),
			new Case("ice_shelf", Biomes.SNOWY_BEACH, "{\"mode\":\"ice_sheet\",\"biomes\":[],\"shelf\":true,\"freeboard\":12,\"thickness\":10,\"dome\":6}"),
			new Case("glacier", Biomes.JAGGED_PEAKS, "{\"mode\":\"glacier\",\"biomes\":[],\"drop\":4,\"snowline\":170}"),
			new Case("wetland", Biomes.SWAMP, "{\"mode\":\"wetland\",\"biomes\":[],\"dry_coverage\":0.4,\"channels\":0.4}"),
			new Case("yardangs", Biomes.DESERT, "{\"mode\":\"yardangs\",\"biomes\":[],\"height\":20,\"spacing\":30,\"length\":150}"),
			new Case("rolling_mesa", Biomes.SAVANNA, "{\"mode\":\"rolling\",\"biomes\":[],\"smooth\":0.85,\"hills\":4,\"mesa_step\":12}"),
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
			ShaperMode mode = ShaperMode.CODEC.parse(ops, JsonParser.parseString(c.json())).getOrThrow();
			BlockPos center = find(level, h -> h.is(c.near()), new BlockPos(k * 3000, 64, -k * 1700));
			if (center == null) {
				BiomesMod.LOGGER.warn("[showcase] {}: no {} found, skipped", c.name(), c.near());
				continue;
			}
			apply(level, mode, center, 256);
			DebugRender.render(level, center, 256, "field_" + c.name());
			BiomesMod.LOGGER.info("[showcase] field_{} at {} | {}", c.name(), center.toShortString(), FieldStats.summary(mode.type()));
		}
	}

	static BlockPos find(ServerLevel level, Predicate<Holder<Biome>> biome, BlockPos from) {
		Pair<BlockPos, Holder<Biome>> p = level.findClosestBiome3d(biome, from, 6400, 32, 64);
		return p == null ? null : new BlockPos(p.getFirst().getX(), 64, p.getFirst().getZ());
	}

	/** Generates the area, then runs the feature once per chunk at the chunk origin, like worldgen does. */
	static void apply(ServerLevel level, ShaperMode mode, BlockPos center, int size) {
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
		RandomSource random = RandomSource.create(42);
		var generator = level.getChunkSource().getGenerator();
		for (int cz = cz0; cz <= cz1; cz++) {
			for (int cx = cx0; cx <= cx1; cx++) {
				BlockPos origin = new BlockPos(cx << 4, LevelCompat.minY(level), cz << 4);
				TerrainFeatures.TERRAIN_SHAPER.place(new FeaturePlaceContext<>(Optional.empty(), level, generator, random, origin, mode));
			}
		}
	}
}
