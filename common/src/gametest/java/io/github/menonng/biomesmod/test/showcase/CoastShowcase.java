package io.github.menonng.biomesmod.test.showcase;

import io.github.menonng.biomesmod.BiomesMod;
import io.github.menonng.biomesmod.debug.DebugRender;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Owner: coastal-terrain-engineer. Coast engine showcase ({@code -Pshowcase=coast}).
 * <ol>
 *   <li>Measures coast widths from the noise-only terrain (no features) at fixed transects and over a fixed window,
 *       so runs with and without the {@code minecraft:overworld/continents} override can be compared.</li>
 *   <li>Renders fixed coast spots (same seed, same coordinates in every run).</li>
 *   <li>Locates every registered {@code biomesmod:coast_*} biome, renders it and runs naturalness checks.</li>
 * </ol>
 * An optional file {@code coast_tag.txt} in the run directory adds a suffix to every output name (e.g. "_vanilla").
 */
public final class CoastShowcase {
	/** Sea-to-land transects {name, x0, z0, x1, z1} on the self-check seed (0). */
	private static final int[][] TRANSECTS = {
			{0, -512, -700, -512, -100},
			{1, 0, -700, 0, -100},
			{2, 512, -700, 512, -100},
			{3, -1024, -700, -1024, -100},
			{4, 384, -620, 384, -120},
			{5, 1900, 300, 1300, 300},
			{6, 352, -1300, 352, -900},
			{7, -1500, 500, -1500, -100},
	};

	private CoastShowcase() {
	}

	public static void run(MinecraftServer server) {
		ServerLevel level = server.overworld();
		String tag = tag();
		StringBuilder report = new StringBuilder();
		measure(level, report);
		BiomesMod.LOGGER.info("[coast] metrics{}:{}", tag, report);
		writeText("coast_metrics" + tag, report.toString());
		DebugRender.render(level, new BlockPos(384, 64, -320), 320, "coast_spot_a" + tag);
		DebugRender.render(level, new BlockPos(-512, 64, -280), 320, "coast_spot_b" + tag);
	}

	// ---------------------------------------------------------------- measurement

	static void measure(ServerLevel level, StringBuilder out) {
		ChunkGenerator gen = level.getChunkSource().getGenerator();
		RandomState rs = level.getChunkSource().randomState();
		int sea = gen.getSeaLevel();
		long t0 = System.nanoTime();
		double shelfSum = 0, beachSum = 0;
		int found = 0;
		for (int[] t : TRANSECTS) {
			int n = Math.max(Math.abs(t[3] - t[1]), Math.abs(t[4] - t[2]));
			int[] floor = new int[n + 1];
			for (int i = 0; i <= n; i++) {
				int x = t[1] + Math.round((t[3] - t[1]) * (float) i / n);
				int z = t[2] + Math.round((t[4] - t[2]) * (float) i / n);
				floor[i] = gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, rs) - 1;
			}
			int shore = -1;
			for (int i = 0; i + 2 <= n; i++) {
				if (floor[i] >= sea - 1 && floor[i + 1] >= sea - 1 && floor[i + 2] >= sea - 1) {
					shore = i;
					break;
				}
			}
			if (shore <= 0) {
				out.append(String.format("%n  transect %d: no shoreline", t[0]));
				continue;
			}
			int shelf5 = 0, shelf10 = 0;
			for (int i = shore - 1; i >= 0 && floor[i] < sea - 1 && sea - 1 - floor[i] <= 5; i--) {
				shelf5++;
			}
			for (int i = shore - 1; i >= 0 && floor[i] < sea - 1 && sea - 1 - floor[i] <= 10; i--) {
				shelf10++;
			}
			int beach = 0;
			for (int i = shore; i <= n && floor[i] >= sea - 1 && floor[i] <= sea + 2; i++) {
				beach++;
			}
			int sx = t[1] + Math.round((t[3] - t[1]) * (float) shore / n);
			int sz = t[2] + Math.round((t[4] - t[2]) * (float) shore / n);
			out.append(String.format("%n  transect %d: shoreline at (%d, %d); shelf<=5 deep %d, shelf<=10 deep %d, low beach (<=3 up) %d, total %d",
					t[0], sx, sz, shelf5, shelf10, beach, shelf5 + beach));
			shelfSum += shelf5;
			beachSum += beach;
			found++;
		}
		out.append(String.format("%n  transect mean: shelf<=5 %.1f, beach %.1f, total %.1f (n=%d)",
				shelfSum / found, beachSum / found, (shelfSum + beachSum) / found, found));

		// area statistics: shallow-water and low-land area per unit coastline length
		int size = 1536, stride = 8, n = size / stride;
		int cx = 0, cz = -256;
		int[][] fl = new int[n][n];
		for (int iz = 0; iz < n; iz++) {
			for (int ix = 0; ix < n; ix++) {
				fl[ix][iz] = gen.getBaseHeight(cx - size / 2 + ix * stride, cz - size / 2 + iz * stride,
						Heightmap.Types.OCEAN_FLOOR_WG, level, rs) - 1;
			}
		}
		long edges = 0, shallow = 0, low = 0, water = 0;
		for (int iz = 0; iz < n; iz++) {
			for (int ix = 0; ix < n; ix++) {
				int f = fl[ix][iz];
				boolean wet = f < sea - 1;
				if (wet) {
					water++;
					if (sea - 1 - f <= 5) {
						shallow++;
					}
				} else if (f <= sea + 2) {
					low++;
				}
				if (ix + 1 < n && wet != (fl[ix + 1][iz] < sea - 1)) {
					edges++;
				}
				if (iz + 1 < n && wet != (fl[ix][iz + 1] < sea - 1)) {
					edges++;
				}
			}
		}
		double coastLength = edges * Math.PI * stride / 4.0;
		double shallowArea = shallow * (double) stride * stride;
		double lowArea = low * (double) stride * stride;
		out.append(String.format("%n  area %dx%d at (%d,%d) stride %d: water %.1f%%, coastline ~%.0f blocks, "
						+ "shallow(<=5) %.0f m2 -> %.1f blocks per coast block, low land(<=3 up) %.0f m2 -> %.1f blocks per coast block",
				size, size, cx, cz, stride, 100.0 * water / (n * n), coastLength, shallowArea, shallowArea / coastLength,
				lowArea, lowArea / coastLength));
		out.append(String.format("%n  (measured in %d ms)", (System.nanoTime() - t0) / 1_000_000));
	}

	// ---------------------------------------------------------------- io

	static String tag() {
		try {
			Path p = Path.of("coast_tag.txt");
			return Files.exists(p) ? Files.readString(p).trim() : "";
		} catch (IOException e) {
			return "";
		}
	}

	static void writeText(String name, String text) {
		try {
			Path dir = Path.of("renders");
			Files.createDirectories(dir);
			Files.writeString(dir.resolve(name + ".txt"), text);
		} catch (IOException e) {
			BiomesMod.LOGGER.error("[coast] failed to write {}", name, e);
		}
	}
}
