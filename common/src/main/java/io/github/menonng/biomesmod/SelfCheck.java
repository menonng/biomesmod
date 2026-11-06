package io.github.menonng.biomesmod;

import com.mojang.datafixers.util.Pair;
import io.github.menonng.biomesmod.compat.Ids;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

/**
 * End-to-end load check, enabled with -Dbiomesmod.selfcheck=true (see the selfCheck Loom run).
 * For every mod biome: registry entry exists, the End biome source can produce it,
 * and /locate-style search actually finds it in generated terrain. Exits the JVM with 0 or 1.
 */
final class SelfCheck {
	private SelfCheck() {
	}

	static void run(MinecraftServer server) {
		List<String> failures = new ArrayList<>();
		ServerLevel end = server.getLevel(Level.END);

		if (end == null) {
			failures.add("End dimension is not loaded");
		} else {
			var biomes = server.registryAccess().lookupOrThrow(Registries.BIOME);
			var possible = end.getChunkSource().getGenerator().getBiomeSource().possibleBiomes();

			for (ResourceKey<Biome> key : ModBiomes.ALL) {
				var holder = biomes.get(key);
				if (holder.isEmpty()) {
					failures.add(Ids.name(key) + ": not in biome registry");
					continue;
				}
				if (!possible.contains(holder.get())) {
					failures.add(Ids.name(key) + ": not a possible biome of the End biome source");
					continue;
				}
				Pair<BlockPos, Holder<Biome>> found = end.findClosestBiome3d(
						h -> h.is(key), new BlockPos(1500, 64, 0), 6400, 32, 64);
				if (found == null) {
					failures.add(Ids.name(key) + ": not found within 6400 blocks in the End");
				} else {
					BiomesMod.LOGGER.info("[selfcheck] {} OK - nearest at {}", Ids.name(key), found.getFirst());
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
}
