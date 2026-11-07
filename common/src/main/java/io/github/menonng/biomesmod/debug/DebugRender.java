package io.github.menonng.biomesmod.debug;

import io.github.menonng.biomesmod.BiomesMod;
import io.github.menonng.biomesmod.compat.LevelCompat;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Dev verification renderer: writes PNGs of generated terrain so worldgen can be checked visually
 * without a client. Enabled in the self-check with -Dbiomesmod.render=true (images land in
 * run/selfcheck/renders/). Owner: coordinator; engine agents may add new render methods.
 */
public final class DebugRender {
	private DebugRender() {
	}

	public static boolean enabled() {
		return Boolean.getBoolean("biomesmod.render");
	}

	/** Side of the self-check renders in blocks (-PrenderSize, default 256; smaller is much faster far from spawn). */
	public static int size() {
		return Integer.getInteger("biomesmod.render.size", 256);
	}

	/**
	 * Top-down map (block map colors, hill-shaded by height, water tinted by depth) of a square of
	 * {@code size} blocks centred on {@code center}, plus a west-east cross-section through the centre.
	 * Generates the chunks it touches. Returns the written top-down file.
	 */
	public static Path render(ServerLevel level, BlockPos center, int size, String name) {
		int half = size / 2;
		int[][] height = new int[size][size];
		BufferedImage map = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		java.util.Set<String> noColour = new java.util.LinkedHashSet<>();
		// Generate (to FULL status) every chunk the image covers, otherwise heightmaps read as empty.
		generate(level, (center.getX() - half) >> 4, (center.getZ() - half) >> 4, (center.getX() - half + size - 1) >> 4,
				(center.getZ() - half + size - 1) >> 4);
		for (int dz = 0; dz < size; dz++) {
			for (int dx = 0; dx < size; dx++) {
				int x = center.getX() - half + dx;
				int z = center.getZ() - half + dz;
				int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
				int floor = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
				height[dx][dz] = top;
				pos.set(x, top, z);
				BlockState state = level.getBlockState(pos);
				int rgb = state.getMapColor(level, pos).col;
				if (rgb == 0 && noColour.size() < 6) {
					noColour.add(state.getBlock().getName().getString() + "@" + x + "," + top + "," + z);
				}
				if (!state.getFluidState().isEmpty() && top > floor) {
					// water: blend towards floor colour by depth
					pos.set(x, floor, z);
					int floorRgb = level.getBlockState(pos).getMapColor(level, pos).col;
					double t = Math.min(1.0, (top - floor) / 12.0);
					rgb = mix(floorRgb, rgb, 0.35 + 0.65 * t);
				}
				map.setRGB(dx, dz, rgb);
			}
		}
		// hill shading from the north-west
		for (int dz = 0; dz < size; dz++) {
			for (int dx = 0; dx < size; dx++) {
				int h = height[dx][dz];
				int hn = height[Math.max(0, dx - 1)][Math.max(0, dz - 1)];
				double shade = 1.0 + Math.max(-0.45, Math.min(0.45, (h - hn) * 0.12));
				map.setRGB(dx, dz, scale(map.getRGB(dx, dz), shade));
			}
		}
		// mark the centre
		for (int i = -3; i <= 3; i++) {
			map.setRGB(Math.clamp(half + i, 0, size - 1), half, 0xFF0000);
			map.setRGB(half, Math.clamp(half + i, 0, size - 1), 0xFF0000);
		}

		// west-east cross-section through the centre
		int minY = LevelCompat.minY(level);
		int maxY = minY + level.getHeight();
		int sectionTop = Math.min(maxY, maxOf(height) + 8);
		int sectionBottom = Math.max(minY, sectionTop - 160);
		BufferedImage section = new BufferedImage(size, sectionTop - sectionBottom, BufferedImage.TYPE_INT_RGB);
		for (int dx = 0; dx < size; dx++) {
			for (int y = sectionBottom; y < sectionTop; y++) {
				pos.set(center.getX() - half + dx, y, center.getZ());
				BlockState state = level.getBlockState(pos);
				int rgb = state.isAir() ? (y >= level.getSeaLevel() ? 0xBFD8FF : 0x101010) : state.getMapColor(level, pos).col;
				section.setRGB(dx, sectionTop - 1 - y, rgb);
			}
		}

		Path dir = Path.of("renders");
		Path top = dir.resolve(name + ".png");
		try {
			Files.createDirectories(dir);
			ImageIO.write(map, "png", top.toFile());
			ImageIO.write(section, "png", dir.resolve(name + "_section.png").toFile());
		} catch (IOException e) {
			BiomesMod.LOGGER.error("[render] failed to write {}", top, e);
		}
		BiomesMod.LOGGER.info("[render] {} -> {}{}", name, top.toAbsolutePath(), noColour.isEmpty() ? "" : " (uncoloured tops: " + noColour + ")");
		return top;
	}

	/**
	 * Generates chunks [cx0..cx1] x [cz0..cz1] to FULL: all are requested at once so the worldgen threads work in
	 * parallel, and the server thread runs its task queue until every one is done. Must run on the server thread.
	 */
	public static void generate(ServerLevel level, int cx0, int cz0, int cx1, int cz1) {
		java.util.List<java.util.concurrent.CompletableFuture<?>> pending = new java.util.ArrayList<>();
		for (int cz = cz0; cz <= cz1; cz++) {
			for (int cx = cx0; cx <= cx1; cx++) {
				pending.add(level.getChunkSource().getChunkFuture(cx, cz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true));
			}
		}
		level.getServer().managedBlock(() -> pending.stream().allMatch(java.util.concurrent.CompletableFuture::isDone));
		for (int cz = cz0; cz <= cz1; cz++) {
			for (int cx = cx0; cx <= cx1; cx++) {
				level.getChunk(cx, cz);
			}
		}
	}

	/**
	 * Overview map of the noise terrain (before features) around {@code center}: {@code px} x {@code px} pixels,
	 * one per {@code step} blocks, sampled from the generator without generating chunks. Colour is the biome (gold
	 * for {@code target}, distinct hues for other mod biomes, muted green for vanilla), brightness the height,
	 * blue the sea. Written as {@code name}_overview.png. Shows a whole site with its ramp and surroundings.
	 */
	public static Path overview(ServerLevel level, BlockPos center, int px, int step, String name, String target) {
		var generator = level.getChunkSource().getGenerator();
		var source = generator.getBiomeSource();
		var sampler = level.getChunkSource().randomState().sampler();
		var world = io.github.menonng.biomesmod.terrain.util.WorldSampler.of(level, generator);
		int sea = level.getSeaLevel();
		int[][] h = new int[px][px];
		int[][] col = new int[px][px];
		int half = px / 2;
		for (int j = 0; j < px; j++) {
			for (int i = 0; i < px; i++) {
				int x = center.getX() + (i - half) * step;
				int z = center.getZ() + (j - half) * step;
				int floor = world.baseFloorHeight(x, z) - 1;
				h[i][j] = floor;
				var biome = source.getNoiseBiome(x >> 2, (Math.max(floor, sea) + 1) >> 2, z >> 2, sampler);
				String id = biome.unwrapKey().map(io.github.menonng.biomesmod.compat.Ids::name).orElse("?");
				int rgb;
				if (id.equals(target)) {
					rgb = 0xE6B84A;
				} else if (id.startsWith(BiomesMod.MOD_ID + ":")) {
					float hue = (id.hashCode() & 0xFFFF) / 65536.0F;
					rgb = java.awt.Color.HSBtoRGB(hue, 0.55F, 0.85F) & 0xFFFFFF;
				} else {
					rgb = 0x6F8F5A;
				}
				double bright = 0.55 + 0.45 * Math.max(0, Math.min(1, (floor - 40) / 200.0));
				rgb = scale(rgb, bright);
				if (floor < sea - 1) {
					double t = Math.min(1.0, (sea - floor) / 30.0);
					rgb = mix(rgb, 0x2F5FAF, 0.35 + 0.6 * t);
				}
				col[i][j] = rgb;
			}
		}
		BufferedImage map = new BufferedImage(px, px, BufferedImage.TYPE_INT_RGB);
		for (int j = 0; j < px; j++) {
			for (int i = 0; i < px; i++) {
				int hn = h[Math.max(0, i - 1)][Math.max(0, j - 1)];
				double shade = 1.0 + Math.max(-0.4, Math.min(0.4, (h[i][j] - hn) * 0.04));
				map.setRGB(i, j, scale(col[i][j], shade));
			}
		}
		for (int k = -4; k <= 4; k++) {
			map.setRGB(Math.clamp(half + k, 0, px - 1), half, 0xFF0000);
			map.setRGB(half, Math.clamp(half + k, 0, px - 1), 0xFF0000);
		}
		Path out = Path.of("renders").resolve(name + "_overview.png");
		try {
			Files.createDirectories(out.getParent());
			ImageIO.write(map, "png", out.toFile());
		} catch (IOException e) {
			BiomesMod.LOGGER.error("[render] failed to write {}", out, e);
		}
		BiomesMod.LOGGER.info("[render] {} overview ({} blocks across) -> {}", name, px * step, out.toAbsolutePath());
		return out;
	}

	private static int maxOf(int[][] a) {
		int m = Integer.MIN_VALUE;
		for (int[] row : a) {
			for (int v : row) {
				m = Math.max(m, v);
			}
		}
		return m;
	}

	private static int mix(int a, int b, double t) {
		int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
		return (r << 16) | (g << 8) | bl;
	}

	private static int scale(int rgb, double f) {
		int r = Math.clamp((int) (((rgb >> 16) & 255) * f), 0, 255);
		int g = Math.clamp((int) (((rgb >> 8) & 255) * f), 0, 255);
		int b = Math.clamp((int) ((rgb & 255) * f), 0, 255);
		return (r << 16) | (g << 8) | b;
	}
}
