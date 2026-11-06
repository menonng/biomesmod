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
				BlockPos inner = interior(level, found.getFirst(), entry);
				BiomesMod.LOGGER.info("[selfcheck] {} OK - nearest at {}, interior {} ({} ms); surface: {}", name, found.getFirst().toShortString(),
						inner.toShortString(), System.currentTimeMillis() - start, surfaceSummary(level, inner, entry));
				String id = entry.key().toString().replaceAll(".*:([a-z0-9_]+)]$", "$1");
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
		return counts.entrySet().stream()
				.sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
				.limit(4)
				.map(e -> e.getKey() + " x" + e.getValue())
				.collect(Collectors.joining(", "));
	}
}
