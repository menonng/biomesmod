package io.github.menonng.biomesmod;

import com.mojang.datafixers.util.Pair;
import io.github.menonng.biomesmod.compat.Ids;
import io.github.menonng.biomesmod.compat.LevelCompat;
import io.github.menonng.biomesmod.debug.DebugRender;
import io.github.menonng.biomesmod.site.SiteConfig;
import io.github.menonng.biomesmod.site.SiteField;
import io.github.menonng.biomesmod.worldgen.Placements;
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
	/** Site biomes are located from the site layout itself, which is cheap, so the search can go much further. */
	private static final int SITE_RADIUS = 60000;
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

		String onlyBiomes = System.getProperty("biomesmod.selfcheck.only", "");
		for (ModBiomes.Entry entry : ModBiomes.ALL) {
			String name = Ids.name(entry.key()) + " [" + Ids.name(entry.dimension()) + "]";
			if (!onlyBiomes.isEmpty() && !List.of(onlyBiomes.split(",")).contains(Ids.name(entry.key()).replace("biomesmod:", ""))) {
				continue;
			}
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
			String id = entry.key().toString().replaceAll(".*:([a-z0-9_]+)]$", "$1");
			SiteConfig.Spec siteSpec = Placements.get().sites().specs().stream()
					.filter(s -> s.biome().equals(Ids.name(entry.key()))).findFirst().orElse(null);
			if (siteSpec != null) {
				checkSite(level, holder.get(), entry, siteSpec, name, id, failures);
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
				// Landforms: probe the largest patch, and the nearest one too when it is a different patch.
				List<int[]> placed = new ArrayList<>();
				landformDiagnostics(level, holder.get(), inner, name, placed);
				BlockPos near = found.getFirst();
				if (near.distSqr(inner) > 768.0 * 768.0) {
					landformDiagnostics(level, holder.get(), near, name + " (nearest patch)", placed);
				}
				String only = System.getProperty("biomesmod.render.only", "");
				if (DebugRender.enabled() && (only.isEmpty() || List.of(only.split(",")).contains(id))) {
					DebugRender.render(level, inner, DebugRender.size(), id);
					if (!placed.isEmpty()) {
						// A second view centred on one placed landform, which the patch-centre view often misses.
						DebugRender.render(level, new BlockPos(placed.get(0)[0], 64, placed.get(0)[1]), DebugRender.size(), id + "_landform");
					}
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

	/**
	 * A site-placed biome: the nearest site from the origin, its biome at the centre, and the noise terrain over
	 * the core (elevation against the target, flat area). Flat plateaus (relief <= 0.05) that target y >= 200 must
	 * hold at least 600,000 m^2 of flat land at y >= 200 (the Salar de Uyuni requirement).
	 */
	private static void checkSite(ServerLevel level, Holder<Biome> biome, ModBiomes.Entry entry, SiteConfig.Spec spec, String name, String id,
			List<String> failures) {
		long start = System.currentTimeMillis();
		var sampler = level.getChunkSource().randomState().sampler();
		SiteField field = SiteField.forSampler(sampler);
		if (field == null) {
			failures.add(name + ": the overworld has no site field (density functions not wrapped?)");
			return;
		}
		List<SiteField.Site> sites = field.sitesNear(0, 0, SITE_RADIUS, spec.biome());
		if (sites.isEmpty()) {
			failures.add(name + ": no site within " + SITE_RADIUS + " blocks");
			return;
		}
		SiteField.Site s = sites.getFirst();
		var world = io.github.menonng.biomesmod.terrain.util.WorldSampler.of(level, level.getChunkSource().getGenerator());
		var source = level.getChunkSource().getGenerator().getBiomeSource();
		int sea = level.getSeaLevel();
		int centreFloor = world.baseFloorHeight(s.x(), s.z()) - 1;
		Holder<Biome> atCentre = source.getNoiseBiome(s.x() >> 2, (Math.max(centreFloor, sea) + 1) >> 2, s.z() >> 2, sampler);
		if (!atCentre.is(entry.key())) {
			failures.add(name + ": site centre " + s.x() + "," + s.z() + " has biome " + atCentre.unwrapKey().map(Ids::name).orElse("?"));
		}
		// Noise terrain over the site, every 16 blocks: elevation inside the core, flat area, water share.
		List<Integer> core = new ArrayList<>();
		int coreCells = 0;
		int flat = 0;
		int flatHigh = 0;
		int wet = 0;
		int r = (int) Math.ceil(s.core() * 1.35);
		for (int dz = -r; dz <= r; dz += 16) {
			for (int dx = -r; dx <= r; dx += 16) {
				int x = s.x() + dx;
				int z = s.z() + dz;
				SiteField.Blend b = field.blend(x, z);
				if (b.a() != s || b.wa() < 0.999) {
					continue;
				}
				coreCells++;
				int h = world.baseFloorHeight(x, z) - 1;
				core.add(h);
				if (h < sea - 1) {
					wet++;
				}
				if (Math.abs(h - spec.targetY()) <= 3) {
					flat++;
					if (h >= 200) {
						flatHigh++;
					}
				}
			}
		}
		java.util.Collections.sort(core);
		String terrain = core.isEmpty() ? "no core samples" : String.format(
				"core %d m^2, noise ground median %d (min %d, max %d), target %s y=%d, within 3 of target %d m^2 (%d m^2 at y>=200), under sea %d%%",
				coreCells * 256, core.get(core.size() / 2), core.getFirst(), core.getLast(), spec.terrain().name().toLowerCase(), spec.targetY(),
				flat * 256, flatHigh * 256, Math.round(100.0 * wet / Math.max(1, coreCells)));
		if (spec.terrain() == SiteConfig.Terrain.PLATEAU && spec.relief() <= 0.05 && spec.targetY() >= 200 && flatHigh * 256 < 600_000) {
			failures.add(name + ": plateau has only " + flatHigh * 256 + " m^2 of flat land at y>=200 (needs 600000)");
		}
		BlockPos inner = new BlockPos(s.x(), 64, s.z());
		LAST_INTERIOR_RADIUS = (int) s.core();
		BiomesMod.LOGGER.info("[selfcheck] {} OK - site {} of {} within {} blocks at {}, {} (core {} + ramp {}) ({} ms); {}; surface: {}", name, 1,
				sites.size(), SITE_RADIUS, s.x(), s.z(), Math.round(s.core()), Math.round(s.ramp()), System.currentTimeMillis() - start, terrain,
				surfaceSummary(level, inner, entry));
		List<int[]> placed = new ArrayList<>();
		landformDiagnostics(level, biome, inner, name, placed);
		String only = System.getProperty("biomesmod.render.only", "");
		boolean selected = DebugRender.enabled() && (only.isEmpty() || List.of(only.split(",")).contains(id));
		if (Boolean.getBoolean("biomesmod.overview")) {
			DebugRender.overview(level, inner, 160, 14, id, spec.biome());
		}
		if (selected) {
			DebugRender.render(level, inner, DebugRender.size(), id);
			if (!placed.isEmpty()) {
				DebugRender.render(level, new BlockPos(placed.get(0)[0], 64, placed.get(0)[1]), DebugRender.size(), id + "_landform");
			}
		}
	}

	/** Logs, for every landform feature of the biome, how many nearby grid cells place an instance and why the rest do not. */
	private static void landformDiagnostics(ServerLevel level, Holder<Biome> biome, BlockPos at, String name, List<int[]> placedOut) {
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
					int before = placedOut.size();
					String d = io.github.menonng.biomesmod.terrain.landform.LandformFeature.diagnose(sampler, cfg, at.getX(), at.getZ(), 768, placedOut);
					StringBuilder where = new StringBuilder();
					for (int i = before; i < Math.min(placedOut.size(), before + 4); i++) {
						where.append(i == before ? " at " : "; ").append(placedOut.get(i)[0]).append(',').append(placedOut.get(i)[1]);
					}
					BiomesMod.LOGGER.info("[selfcheck] {} landform {}{}", name, d, where);
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
		// heightmaps of ungenerated chunks read as empty
		DebugRender.generate(level, (center.getX() - 32) >> 4, (center.getZ() - 32) >> 4, (center.getX() + 31) >> 4, (center.getZ() + 31) >> 4);
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
