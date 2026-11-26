package io.github.menonng.biomesmod.debug

import io.github.menonng.biomesmod.BiomesMod
import io.github.menonng.biomesmod.compat.Ids
import io.github.menonng.biomesmod.compat.LevelCompat
import io.github.menonng.biomesmod.terrain.util.WorldSampler
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import io.github.menonng.biomesmod.compat.ChunkStatusCompat as ChunkStatus
import net.minecraft.world.level.levelgen.Heightmap
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Dev verification renderer: writes PNGs of generated terrain so worldgen can be checked visually without a client.
 * Enabled in the self-check with -Dbiomesmod.render=true (images land in run/selfcheck/renders/).
 */
object DebugRender {
	fun enabled(): Boolean = java.lang.Boolean.getBoolean("biomesmod.render")

	/** Side of the self-check renders in blocks (-PrenderSize, default 256; smaller is much faster far from spawn). */
	fun size(): Int = Integer.getInteger("biomesmod.render.size", 256)

	/**
	 * Top-down map (block map colors, hill-shaded by height, water tinted by depth) of a square of [size] blocks centred
	 * on [center], plus a west-east cross-section through the centre. Generates the chunks it touches. Returns the
	 * written top-down file.
	 */
	fun render(level: ServerLevel, center: BlockPos, size: Int, name: String): Path {
		val half = size / 2
		val height = Array(size) { IntArray(size) }
		val map = BufferedImage(size, size, BufferedImage.TYPE_INT_RGB)
		val pos = BlockPos.MutableBlockPos()
		val noColour = LinkedHashSet<String>()
		// Generate (to FULL status) every chunk the image covers, otherwise heightmaps read as empty.
		generate(level, (center.x - half) shr 4, (center.z - half) shr 4, (center.x - half + size - 1) shr 4, (center.z - half + size - 1) shr 4)
		for (dz in 0 until size) {
			for (dx in 0 until size) {
				val x = center.x - half + dx
				val z = center.z - half + dz
				val top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1
				val floor = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1
				height[dx][dz] = top
				pos.set(x, top, z)
				val state = level.getBlockState(pos)
				var rgb = state.getMapColor(level, pos).col
				if (rgb == 0 && noColour.size < 6) {
					noColour.add("${state.block.name.string}@$x,$top,$z")
				}
				if (!state.fluidState.isEmpty && top > floor) {
					// water: blend towards floor colour by depth
					pos.set(x, floor, z)
					val floorRgb = level.getBlockState(pos).getMapColor(level, pos).col
					val t = min(1.0, (top - floor) / 12.0)
					rgb = mix(floorRgb, rgb, 0.35 + 0.65 * t)
				}
				map.setRGB(dx, dz, rgb)
			}
		}
		// hill shading from the north-west
		for (dz in 0 until size) {
			for (dx in 0 until size) {
				val h = height[dx][dz]
				val hn = height[max(0, dx - 1)][max(0, dz - 1)]
				val shade = 1.0 + max(-0.45, min(0.45, (h - hn) * 0.12))
				map.setRGB(dx, dz, scale(map.getRGB(dx, dz), shade))
			}
		}
		// mark the centre
		for (i in -3..3) {
			map.setRGB((half + i).coerceIn(0, size - 1), half, 0xFF0000)
			map.setRGB(half, (half + i).coerceIn(0, size - 1), 0xFF0000)
		}

		// west-east cross-section through the centre
		val minY = LevelCompat.minY(level)
		val maxY = minY + level.height
		val sectionTop = min(maxY, height.maxOf { it.max() } + 8)
		val sectionBottom = max(minY, sectionTop - 160)
		val section = BufferedImage(size, sectionTop - sectionBottom, BufferedImage.TYPE_INT_RGB)
		for (dx in 0 until size) {
			for (y in sectionBottom until sectionTop) {
				pos.set(center.x - half + dx, y, center.z)
				val state = level.getBlockState(pos)
				val rgb = if (state.isAir) (if (y >= level.seaLevel) 0xBFD8FF else 0x101010) else state.getMapColor(level, pos).col
				section.setRGB(dx, sectionTop - 1 - y, rgb)
			}
		}

		val dir = Path.of("renders")
		val top = dir.resolve("$name.png")
		try {
			Files.createDirectories(dir)
			ImageIO.write(map, "png", top.toFile())
			ImageIO.write(section, "png", dir.resolve("${name}_section.png").toFile())
		} catch (e: IOException) {
			BiomesMod.LOGGER.error("[render] failed to write {}", top, e)
		}
		BiomesMod.LOGGER.info("[render] {} -> {}{}", name, top.toAbsolutePath(), if (noColour.isEmpty()) "" else " (uncoloured tops: $noColour)")
		return top
	}

	/**
	 * Isometric voxel view of a square of [size] blocks centred on [center], seen from the south-east and above
	 * (overhangs, arches and pillar shapes show, which a top-down map cannot). Faces are shaded by orientation, the top
	 * slightly by height; water is drawn as a translucent top. Written as renders/`name`_iso.png. The chunks must be
	 * generated already (call after [render]).
	 */
	fun iso(level: ServerLevel, center: BlockPos, size: Int, name: String, s: Int = 2, bare: Boolean = false): Path {
		val half = size / 2
		val x0 = center.x - half
		val z0 = center.z - half
		val pos = BlockPos.MutableBlockPos()
		var yLo = Int.MAX_VALUE
		var yHi = Int.MIN_VALUE
		for (dz in 0 until size step 2) {
			for (dx in 0 until size step 2) {
				val t = level.getHeight(Heightmap.Types.WORLD_SURFACE, x0 + dx, z0 + dz) - 1
				val f = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x0 + dx, z0 + dz) - 1
				yLo = min(yLo, f)
				yHi = max(yHi, t)
			}
		}
		yLo = max(LevelCompat.minY(level), yLo - 3)
		yHi += 2
		val w = size * 4 * s + 8
		val h = size * 2 * s + (yHi - yLo) * 2 * s + 8
		val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
		val g = img.graphics
		g.color = Color(0xC8D8EA)
		g.fillRect(0, 0, w, h)
		val ox = size * 2 * s + 4
		val oy = (yHi - yLo) * 2 * s + 4
		fun state(x: Int, y: Int, z: Int) = level.getBlockState(pos.set(x, y, z))
		fun skip(st: net.minecraft.world.level.block.state.BlockState): Boolean =
			st.isAir || (bare && (st.`is`(net.minecraft.tags.BlockTags.LEAVES) || st.`is`(net.minecraft.tags.BlockTags.LOGS) || (!st.canOcclude() && st.fluidState.isEmpty)))
		fun open(x: Int, y: Int, z: Int): Boolean {
			val st = state(x, y, z)
			return skip(st) || !st.canOcclude()
		}
		fun fill(cx: Int, cy: Int, face: Int, rgb: Int) {
			// face 0 top rhombus, 1 south (left) parallelogram, 2 east (right) parallelogram; one block = 4s x 2s top.
			when (face) {
				0 -> for (dy in -s until s) {
					val half2 = 2 * s * (s - abs(dy + 0.5)) / s
					for (dx in (-half2).toInt() until half2.toInt()) setPx(img, cx + dx, cy + dy, rgb)
				}
				1 -> for (dx in -2 * s until 0) {
					val y0 = cy + ((dx + 2 * s) / 2.0).toInt()
					for (dy in 0 until 2 * s) setPx(img, cx + dx, y0 + dy, rgb)
				}
				else -> for (dx in 0 until 2 * s) {
					val y0 = cy + s - ((dx + 1) / 2.0).toInt()
					for (dy in 0 until 2 * s) setPx(img, cx + dx, y0 + dy, rgb)
				}
			}
		}
		for (d in 0..2 * (size - 1)) {
			for (i in max(0, d - size + 1)..min(size - 1, d)) {
				val k = d - i
				val x = x0 + i
				val z = z0 + k
				val top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1
				for (y in yLo..top) {
					val st = state(x, y, z)
					if (skip(st)) {
						continue
					}
					val water = !st.fluidState.isEmpty && !st.canOcclude()
					val up = open(x, y + 1, z)
					if (water) {
						if (!up || !state(x, y + 1, z).fluidState.isEmpty) {
							continue
						}
					}
					val south = open(x, y, z + 1)
					val east = open(x + 1, y, z)
					if (!up && !south && !east) {
						continue
					}
					var rgb = flowerColour(st) ?: st.getMapColor(level, pos.set(x, y, z)).col
					if (rgb == 0) {
						rgb = 0x7F7F7F
					}
					val cx = ox + (i - k) * 2 * s
					val cy = oy + (i + k) * s - (y - yLo) * 2 * s
					if (water) {
						fill(cx, cy, 0, mix(rgb, 0x3060C0, 0.35))
						continue
					}
					val lift = 0.9 + 0.2 * (y - yLo) / max(1.0, (yHi - yLo).toDouble())
					if (south) fill(cx, cy, 1, scale(rgb, 0.78 * lift))
					if (east) fill(cx, cy, 2, scale(rgb, 0.6 * lift))
					if (up) fill(cx, cy, 0, scale(rgb, 1.0 * lift))
				}
			}
		}
		g.dispose()
		val out = Path.of("renders").resolve("${name}${if (bare) "_bare" else ""}_iso.png")
		try {
			Files.createDirectories(out.parent)
			ImageIO.write(img, "png", out.toFile())
		} catch (e: IOException) {
			BiomesMod.LOGGER.error("[render] failed to write {}", out, e)
		}
		return out
	}

	/** Small flowers all share the plant map colour; give the common ones their petal colour so fields show. */
	private fun flowerColour(st: net.minecraft.world.level.block.state.BlockState): Int? {
		if (!st.`is`(net.minecraft.tags.BlockTags.SMALL_FLOWERS)) return null
		val n = st.block.descriptionId
		return when {
			"red_tulip" in n || "poppy" in n -> 0xD8332A
			"orange_tulip" in n -> 0xEE8A22
			"pink_tulip" in n -> 0xF0A0C8
			"white_tulip" in n || "oxeye" in n || "lily" in n -> 0xF2F2EC
			"allium" in n -> 0xB66BDB
			"dandelion" in n -> 0xF5D63A
			"cornflower" in n || "orchid" in n -> 0x4F7FE0
			else -> 0xE0D0E8
		}
	}

	private fun setPx(img: BufferedImage, x: Int, y: Int, rgb: Int) {
		if (x >= 0 && y >= 0 && x < img.width && y < img.height) {
			img.setRGB(x, y, rgb)
		}
	}

	/**
	 * Large top-down map ("satellite view") of a square of [size] blocks centred on [center], one pixel per [step]
	 * blocks: block map colours, water tinted by depth, hill-shaded by height. Chunks are loaded (or generated) one row
	 * at a time, so whole sites can be mapped from a pre-generated world. Written as maps/`name`.png.
	 */
	fun map(level: ServerLevel, center: BlockPos, size: Int, step: Int, name: String): Path {
		val px = size / step
		val half = size / 2
		val height = Array(px) { IntArray(px) }
		val img = BufferedImage(px, px, BufferedImage.TYPE_INT_RGB)
		val pos = BlockPos.MutableBlockPos()
		val x0 = center.x - half
		val z0 = center.z - half
		var loadedRow = Int.MIN_VALUE
		for (j in 0 until px) {
			val z = z0 + j * step
			if (z shr 4 != loadedRow) {
				loadedRow = z shr 4
				generate(level, x0 shr 4, loadedRow, (x0 + size - 1) shr 4, loadedRow)
			}
			for (i in 0 until px) {
				val x = x0 + i * step
				val top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1
				val floor = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1
				height[i][j] = top
				pos.set(x, top, z)
				val state = level.getBlockState(pos)
				var rgb = state.getMapColor(level, pos).col
				if (!state.fluidState.isEmpty && top > floor) {
					pos.set(x, floor, z)
					val floorRgb = level.getBlockState(pos).getMapColor(level, pos).col
					rgb = mix(floorRgb, rgb, 0.35 + 0.65 * min(1.0, (top - floor) / 12.0))
				}
				img.setRGB(i, j, rgb)
			}
		}
		for (j in 0 until px) {
			for (i in 0 until px) {
				val hn = height[max(0, i - 1)][max(0, j - 1)]
				val shade = 1.0 + max(-0.45, min(0.45, (height[i][j] - hn) * 0.12 / step))
				img.setRGB(i, j, scale(img.getRGB(i, j), shade))
			}
		}
		val out = Path.of("maps").resolve("$name.png")
		try {
			Files.createDirectories(out.parent)
			ImageIO.write(img, "png", out.toFile())
		} catch (e: IOException) {
			BiomesMod.LOGGER.error("[render] failed to write {}", out, e)
		}
		BiomesMod.LOGGER.info("[render] map {} ({} blocks, {} per pixel) -> {}", name, size, step, out.toAbsolutePath())
		return out
	}

	/**
	 * Renders every map of a plan file (`[{"name": "...", "x": 0, "z": 0, "size": 1024, "step": 2}]`) once the server
	 * has started; enabled with `-Dbiomesmod.maps=<plan.json>` (dev tooling for the docs gallery).
	 */
	/**
	 * Renders every map of a plan (`[{"name", "x", "z", "size", "step"}]`) on a background thread while the server keeps
	 * ticking: each map is generated in bands of chunk rows that are force-loaded (so the chunk system generates them in
	 * parallel and keeps them until drawn), drawn, then released and saved. Calls [done] on the server thread at the end.
	 */
	fun mapPlan(server: net.minecraft.server.MinecraftServer, plan: String, done: () -> Unit = {}) {
		val entries = com.google.gson.JsonParser.parseString(Files.readString(Path.of(plan))).asJsonArray
		Thread({
			val t0 = System.currentTimeMillis()
			for ((k, e) in entries.withIndex()) {
				val o = e.asJsonObject
				try {
					mapAsync(server, BlockPos(o.get("x").asInt, 64, o.get("z").asInt), o.get("size").asInt, o.get("step")?.asInt ?: 2, o.get("name").asString)
				} catch (ex: Exception) {
					BiomesMod.LOGGER.error("[render] map {} failed", o.get("name").asString, ex)
				}
				BiomesMod.LOGGER.info("[render] map plan {}/{} after {} s", k + 1, entries.size(), (System.currentTimeMillis() - t0) / 1000)
			}
			BiomesMod.LOGGER.info("[render] map plan done ({} map(s))", entries.size())
			server.execute(done)
		}, "biomesmod-maps").start()
	}

	private fun mapAsync(server: net.minecraft.server.MinecraftServer, center: BlockPos, size: Int, step: Int, name: String) {
		val level = server.overworld()
		val px = size / step
		val half = size / 2
		val height = Array(px) { IntArray(px) }
		val img = BufferedImage(px, px, BufferedImage.TYPE_INT_RGB)
		val x0 = center.x - half
		val z0 = center.z - half
		val cx0 = x0 shr 4
		val cx1 = (x0 + size - 1) shr 4
		val band = 6
		var cz = z0 shr 4
		val czEnd = (z0 + size - 1) shr 4
		while (cz <= czEnd) {
			val bz0 = cz
			val bz1 = min(czEnd, cz + band - 1)
			server.submit<Unit> { for (z in bz0..bz1) for (x in cx0..cx1) level.setChunkForced(x, z, true) }.join()
			val deadline = System.currentTimeMillis() + 600_000
			while (System.currentTimeMillis() < deadline) {
				val ready = server.submit<Boolean> { (bz0..bz1).all { z -> (cx0..cx1).all { x -> level.chunkSource.hasChunk(x, z) } } }.join()
				if (ready) break
				Thread.sleep(200)
			}
			server.submit<Unit> {
				val pos = BlockPos.MutableBlockPos()
				for (j in 0 until px) {
					val z = z0 + j * step
					if (z shr 4 < bz0 || z shr 4 > bz1) continue
					for (i in 0 until px) {
						val x = x0 + i * step
						val top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1
						val floor = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1
						height[i][j] = top
						pos.set(x, top, z)
						val state = level.getBlockState(pos)
						var rgb = state.getMapColor(level, pos).col
						if (!state.fluidState.isEmpty && top > floor) {
							pos.set(x, floor, z)
							val floorRgb = level.getBlockState(pos).getMapColor(level, pos).col
							rgb = mix(floorRgb, rgb, 0.35 + 0.65 * min(1.0, (top - floor) / 12.0))
						}
						img.setRGB(i, j, rgb)
					}
				}
				for (z in bz0..bz1) for (x in cx0..cx1) level.setChunkForced(x, z, false)
			}.join()
			cz = bz1 + 1
		}
		for (j in 0 until px) {
			for (i in 0 until px) {
				val hn = height[max(0, i - 1)][max(0, j - 1)]
				val shade = 1.0 + max(-0.45, min(0.45, (height[i][j] - hn) * 0.12 / step))
				img.setRGB(i, j, scale(img.getRGB(i, j), shade))
			}
		}
		val out = Path.of("maps").resolve("$name.png")
		try {
			Files.createDirectories(out.parent)
			ImageIO.write(img, "png", out.toFile())
		} catch (e: IOException) {
			BiomesMod.LOGGER.error("[render] failed to write {}", out, e)
		}
		BiomesMod.LOGGER.info("[render] map {} ({} blocks, {} per pixel) -> {}", name, size, step, out.toAbsolutePath())
	}

	/**
	 * Generates chunks [cx0..cx1] x [cz0..cz1] to FULL: all are requested at once so the worldgen threads work in
	 * parallel, and the server thread runs its task queue until every one is done. Must run on the server thread.
	 */
	fun generate(level: ServerLevel, cx0: Int, cz0: Int, cx1: Int, cz1: Int) {
		val pending = ArrayList<CompletableFuture<*>>()
		for (cz in cz0..cz1) {
			for (cx in cx0..cx1) {
				pending.add(level.chunkSource.getChunkFuture(cx, cz, ChunkStatus.FULL, true))
			}
		}
		level.server.managedBlock { pending.all { it.isDone } }
		for (cz in cz0..cz1) {
			for (cx in cx0..cx1) {
				level.getChunk(cx, cz)
			}
		}
	}

	/**
	 * Overview map of the noise terrain (before features) around [center]: [px] x [px] pixels, one per [step] blocks,
	 * sampled from the generator without generating chunks. Colour is the biome (gold for [target], distinct hues for
	 * other mod biomes, muted green for vanilla), brightness the height, blue the sea. Written as `name`_overview.png.
	 * Shows a whole site with its ramp and surroundings.
	 */
	fun overview(level: ServerLevel, center: BlockPos, px: Int, step: Int, name: String, target: String): Path {
		val generator = level.chunkSource.generator
		val source = generator.biomeSource
		val sampler = level.chunkSource.randomState().sampler()
		val world = WorldSampler.of(level, generator)
		val sea = level.seaLevel
		val h = Array(px) { IntArray(px) }
		val col = Array(px) { IntArray(px) }
		val half = px / 2
		for (j in 0 until px) {
			for (i in 0 until px) {
				val x = center.x + (i - half) * step
				val z = center.z + (j - half) * step
				val floor = world.baseFloorHeight(x, z) - 1
				h[i][j] = floor
				val biome = source.getNoiseBiome(x shr 2, (max(floor, sea) + 1) shr 2, z shr 2, sampler)
				val id = biome.unwrapKey().map { Ids.name(it) }.orElse("?")
				var rgb = when {
					id == target -> 0xE6B84A
					id.startsWith(BiomesMod.MOD_ID + ":") -> Color.HSBtoRGB((id.hashCode() and 0xFFFF) / 65536.0f, 0.55f, 0.85f) and 0xFFFFFF
					else -> 0x6F8F5A
				}
				val bright = 0.55 + 0.45 * max(0.0, min(1.0, (floor - 40) / 200.0))
				rgb = scale(rgb, bright)
				if (floor < sea - 1) {
					val t = min(1.0, (sea - floor) / 30.0)
					rgb = mix(rgb, 0x2F5FAF, 0.35 + 0.6 * t)
				}
				col[i][j] = rgb
			}
		}
		val map = BufferedImage(px, px, BufferedImage.TYPE_INT_RGB)
		for (j in 0 until px) {
			for (i in 0 until px) {
				val hn = h[max(0, i - 1)][max(0, j - 1)]
				val shade = 1.0 + max(-0.4, min(0.4, (h[i][j] - hn) * 0.04))
				map.setRGB(i, j, scale(col[i][j], shade))
			}
		}
		for (k in -4..4) {
			map.setRGB((half + k).coerceIn(0, px - 1), half, 0xFF0000)
			map.setRGB(half, (half + k).coerceIn(0, px - 1), 0xFF0000)
		}
		val out = Path.of("renders").resolve("${name}_overview.png")
		try {
			Files.createDirectories(out.parent)
			ImageIO.write(map, "png", out.toFile())
		} catch (e: IOException) {
			BiomesMod.LOGGER.error("[render] failed to write {}", out, e)
		}
		BiomesMod.LOGGER.info("[render] {} overview ({} blocks across) -> {}", name, px * step, out.toAbsolutePath())
		return out
	}

	private fun mix(a: Int, b: Int, t: Double): Int {
		val r = (((a shr 16) and 255) * (1 - t) + ((b shr 16) and 255) * t).toInt()
		val g = (((a shr 8) and 255) * (1 - t) + ((b shr 8) and 255) * t).toInt()
		val bl = ((a and 255) * (1 - t) + (b and 255) * t).toInt()
		return (r shl 16) or (g shl 8) or bl
	}

	private fun scale(rgb: Int, f: Double): Int {
		val r = (((rgb shr 16) and 255) * f).toInt().coerceIn(0, 255)
		val g = (((rgb shr 8) and 255) * f).toInt().coerceIn(0, 255)
		val b = ((rgb and 255) * f).toInt().coerceIn(0, 255)
		return (r shl 16) or (g shl 8) or b
	}
}
