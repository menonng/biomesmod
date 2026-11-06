package io.github.menonng.biomesmod;

import com.mojang.datafixers.util.Pair;
import io.github.menonng.biomesmod.compat.Ids;
import io.github.menonng.biomesmod.compat.LevelCompat;
import io.github.menonng.biomesmod.debug.DebugRender;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * End-to-end load check, enabled with -Dbiomesmod.selfcheck=true (see the selfCheck Loom run).
 * For every mod biome, in its own dimension: the registry entry exists, the dimension's biome source
 * can produce it, and a /locate-style search finds it in generated terrain. Exits the JVM with 0 or 1.
 */
final class SelfCheck {
	private static final int RADIUS = 12000;
	/** Distance from the last interior point to the patch edge (blocks), for logs. */
	private static int LAST_INTERIOR_RADIUS;

	private SelfCheck() {
	}

	static void run(MinecraftServer server) {
		if (!System.getProperty("biomesmod.showcase", "").isEmpty()) {
			return; // a dev showcase owns this run
		}
		List<String> failures = new ArrayList<>();
		var biomes = server.registryAccess().lookupOrThrow(Registries.BIOME);

		for (ModBiomes.Entry entry : ModBiomes.ALL) {
			String name = Ids.name(entry.key()) + " [" + Ids.name(entry.dimension()) + "]";
			ServerLevel level = server.getLevel(entry.dimension());
			if (level == null) {
				failures.add(name + ": dimension is not loaded");
				continue;
			}
			var holder = biomes.get(entry.key());
			if (holder.isEmpty()) {
				failures.add(name + ": not in biome registry");
				continue;
			}
			if (!level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes().contains(holder.get())) {
				failures.add(name + ": not a possible biome of the dimension's biome source");
				continue;
			}
			// End highlands start ~1000 blocks out; elsewhere search from the origin.
			BlockPos origin = entry.dimension() == Level.END ? new BlockPos(1500, 64, 0) : new BlockPos(0, 64, 0);
			long start = System.currentTimeMillis();
			Pair<BlockPos, Holder<Biome>> found = level.findClosestBiome3d(h -> h.is(entry.key()), origin, RADIUS, 32, 64);
			if (found == null) {
				failures.add(name + ": not found within " + RADIUS + " blocks");
			} else {
				// Several search origins: render the largest patch found, not just the one nearest the origin.
				BlockPos inner = interior(level, found.getFirst(), entry);
				int bestR = LAST_INTERIOR_RADIUS;
				if (entry.dimension() == Level.OVERWORLD) {
					for (int[] o : new int[][] {{4000, 0}, {-4000, 0}, {0, 4000}, {0, -4000}}) {
						Pair<BlockPos, Holder<Biome>> f2 = level.findClosestBiome3d(h -> h.is(entry.key()), new BlockPos(o[0], 64, o[1]), 3000, 32, 64);
						if (f2 != null) {
							BlockPos in2 = interior(level, f2.getFirst(), entry);
							if (LAST_INTERIOR_RADIUS > bestR) {
								bestR = LAST_INTERIOR_RADIUS;
								inner = in2;
							}
						}
					}
				}
				LAST_INTERIOR_RADIUS = bestR;
				BiomesMod.LOGGER.info("[selfcheck] {} OK - nearest at {}, interior {} r={} ({} ms); surface: {}", name, found.getFirst().toShortString(),
						inner.toShortString(), LAST_INTERIOR_RADIUS, System.currentTimeMillis() - start, surfaceSummary(level, inner, entry));
				String id = entry.key().toString().replaceAll(".*:([a-z0-9_]+)]$", "$1");
				landformDiagnostics(level, holder.get(), inner, name);
				String only = System.getProperty("biomesmod.render.only", "");
				if (DebugRender.enabled() && (only.isEmpty() || List.of(only.split(",")).contains(id))) {
					DebugRender.render(level, inner, 256, id);
				}
			}
		}

		int code = failures.isEmpty() ? 0 : 1;
		if (code == 0) {
			BiomesMod.LOGGER.info("[selfcheck] PASSED ({} biome(s))", ModBiomes.ALL.size());
		} else {
			failures.forEach(f -> BiomesMod.LOGGER.error("[selfcheck] FAIL {}", f));
		}
		// Exit from another thread so the server's shutdown hook can stop the server thread cleanly.
		new Thread(() -> System.exit(code), "biomesmod-selfcheck-exit").start();
	}

	/** Logs, for every landform feature of the biome, how many nearby grid cells place an instance and why the rest do not. */
	private static void landformDiagnostics(ServerLevel level, Holder<Biome> biome, BlockPos at, String name) {
		var sampler = io.github.menonng.biomesmod.terrain.util.WorldSampler.of(level, level.getChunkSource().getGenerator());
		int floor = sampler.baseFloorHeight(at.getX(), at.getZ()) - 1;
		BiomesMod.LOGGER.info("[selfcheck] {} probe at {}: noise floor {}, sampler biome@floor {}, @64 {}, level biome@floor {}", name, at.toShortString(), floor,
				sampler.biomeAt(at.getX(), Math.max(floor, sampler.seaLevel()), at.getZ()).unwrapKey().map(Ids::name).orElse("?"),
				sampler.biomeAt(at.getX(), 64, at.getZ()).unwrapKey().map(Ids::name).orElse("?"),
				level.getBiome(at.atY(Math.max(floor, sampler.seaLevel()))).unwrapKey().map(Ids::name).orElse("?")
						+ ", field sampler (top of world) " + io.github.menonng.biomesmod.terrain.field.FieldSampler.of(level, level.getChunkSource().getGenerator())
								.biomeNode(at.getX() >> 3, at.getZ() >> 3, 3).unwrapKey().map(Ids::name).orElse("?"));
		for (var step : biome.value().getGenerationSettings().features()) {
			for (var placed : step) {
				if (placed.value().feature().value().config() instanceof io.github.menonng.biomesmod.terrain.landform.LandformConfig cfg) {
					BiomesMod.LOGGER.info("[selfcheck] {} landform {}", name,
							io.github.menonng.biomesmod.terrain.landform.LandformFeature.diagnose(sampler, cfg, at.getX(), at.getZ(), 768));
				}
			}
		}
	}

	/**
	 * The point of the biome patch around {@code near} that lies farthest from its edge (sampled on a 16-block grid
	 * over 768x768 blocks with the generator's own biome source, so nothing is generated). The nearest point that
	 * findClosestBiome3d returns is on the patch edge, which shows mostly the neighbours in a render.
	 */
	private static BlockPos interior(ServerLevel level, BlockPos near, ModBiomes.Entry entry) {
		final int step = 16;
		final int n = 49;
		var source = level.getChunkSource().getGenerator().getBiomeSource();
		var sampler = level.getChunkSource().randomState().sampler();
		boolean[] in = new boolean[n * n];
		int x0 = near.getX() - (n / 2) * step;
		int z0 = near.getZ() - (n / 2) * step;
		int qy = near.getY() >> 2;
		for (int j = 0; j < n; j++) {
			for (int i = 0; i < n; i++) {
				in[i + j * n] = source.getNoiseBiome((x0 + i * step) >> 2, qy, (z0 + j * step) >> 2, sampler).is(entry.key());
			}
		}
		int best = -1;
		int bestD = -1;
		for (int j = 0; j < n; j++) {
			for (int i = 0; i < n; i++) {
				if (!in[i + j * n]) {
					continue;
				}
				int d = Integer.MAX_VALUE;
				for (int v = 0; v < n && d > 0; v++) {
					for (int u = 0; u < n; u++) {
						if (!in[u + v * n]) {
							d = Math.min(d, (u - i) * (u - i) + (v - j) * (v - j));
						}
					}
				}
				if (d > bestD) {
					bestD = d;
					best = i + j * n;
				}
			}
		}
		LAST_INTERIOR_RADIUS = best < 0 ? 0 : (int) Math.round(Math.sqrt(bestD) * step);
		return best < 0 ? near : new BlockPos(x0 + (best % n) * step, near.getY(), z0 + (best / n) * step);
	}

	/**
	 * Most common floor blocks (solid block with air above) inside the biome, in a 32x32 area
	 * around {@code center}. Diagnostic for checking surface rules and features.
	 */
	private static String surfaceSummary(ServerLevel level, BlockPos center, ModBiomes.Entry entry) {
		Map<String, Integer> counts = new HashMap<>();
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dx = -16; dx < 16; dx++) {
			for (int dz = -16; dz < 16; dz++) {
				int x = center.getX() + dx;
				int z = center.getZ() + dz;
				for (int y = LevelCompat.minY(level) + 1; y < LevelCompat.minY(level) + level.getHeight() - 1; y++) {
					pos.set(x, y, z);
					BlockState state = level.getBlockState(pos);
					if (state.isAir() || !level.getBlockState(pos.above()).isAir() || !level.getBiome(pos).is(entry.key())) {
						continue;
					}
					counts.merge(state.getBlock().getName().getString(), 1, Integer::sum);
				}
			}
		}
		String blocks = counts.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.limit(4)
				.map(e -> e.getKey() + " x" + e.getValue())
				.collect(Collectors.joining(", "));
		return blocks + terrainStats(level, center, entry);
	}

	/**
	 * Ground height stats of the biome's columns in a 64x64 area: median/min/max of the top solid block
	 * (OCEAN_FLOOR heightmap) and the share of columns with water over the ground. Verifies terrain shaping
	 * numerically, e.g. that a polder floor really lies below sea level and stays dry.
	 */
	private static String terrainStats(ServerLevel level, BlockPos center, ModBiomes.Entry entry) {
		if (entry.dimension() != Level.OVERWORLD) {
			return "";
		}
		for (int cz = (center.getZ() - 32) >> 4; cz <= (center.getZ() + 31) >> 4; cz++) {
			for (int cx = (center.getX() - 32) >> 4; cx <= (center.getX() + 31) >> 4; cx++) {
				level.getChunk(cx, cz); // heightmaps of ungenerated chunks read as empty
			}
		}
		List<Integer> floors = new ArrayList<>();
		int wet = 0;
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int dx = -32; dx < 32; dx += 2) {
			for (int dz = -32; dz < 32; dz += 2) {
				int x = center.getX() + dx;
				int z = center.getZ() + dz;
				// Real ground: scan down from the surface past leaves, logs, plants and fluids.
				int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z) - 1;
				boolean fluid = false;
				while (y > LevelCompat.minY(level)) {
					pos.set(x, y, z);
					BlockState st = level.getBlockState(pos);
					if (!st.getFluidState().isEmpty()) {
						fluid = true;
					}
					if (st.isSolid() && !st.is(net.minecraft.tags.BlockTags.LEAVES) && !st.is(net.minecraft.tags.BlockTags.LOGS)
							&& st.getFluidState().isEmpty()) {
						break;
					}
					y--;
				}
				pos.set(x, y, z);
				if (!level.getBiome(pos).is(entry.key())) {
					continue;
				}
				floors.add(y);
				if (fluid) {
					wet++;
				}
			}
		}
		if (floors.isEmpty()) {
			return "";
		}
		java.util.Collections.sort(floors);
		return String.format("; ground median %d (min %d, max %d, sea %d), wet %d%% of %d columns", floors.get(floors.size() / 2),
				floors.getFirst(), floors.getLast(), level.getSeaLevel(), Math.round(100.0 * wet / floors.size()), floors.size());
	}
}
