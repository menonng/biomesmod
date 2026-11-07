package io.github.menonng.biomesmod.test.showcase

import io.github.menonng.biomesmod.BiomesMod
import io.github.menonng.biomesmod.debug.DebugRender
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.levelgen.Heightmap
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Coast engine showcase (`-Pshowcase=coast`).
 * 1. Measures coast widths from the noise-only terrain (no features) at fixed transects and over a fixed window, so runs
 *    with and without the `minecraft:overworld/continents` override can be compared.
 * 2. Renders fixed coast spots (same seed, same coordinates in every run).
 *
 * An optional file `coast_tag.txt` in the run directory adds a suffix to every output name (e.g. "_vanilla").
 */
object CoastShowcase {
	/** Sea-to-land transects {name, x0, z0, x1, z1} on the self-check seed (0). */
	private val TRANSECTS = arrayOf(
		intArrayOf(0, -512, -700, -512, -100),
		intArrayOf(1, 0, -700, 0, -100),
		intArrayOf(2, 512, -700, 512, -100),
		intArrayOf(3, -1024, -700, -1024, -100),
		intArrayOf(4, 384, -620, 384, -120),
		intArrayOf(5, 1900, 300, 1300, 300),
		intArrayOf(6, 352, -1300, 352, -900),
		intArrayOf(7, -1500, 500, -1500, -100),
	)

	fun run(server: MinecraftServer) {
		val level = server.overworld()
		val tag = tag()
		val report = StringBuilder()
		measure(level, report)
		BiomesMod.LOGGER.info("[coast] metrics{}:{}", tag, report)
		writeText("coast_metrics$tag", report.toString())
		DebugRender.render(level, BlockPos(384, 64, -320), 320, "coast_spot_a$tag")
		DebugRender.render(level, BlockPos(-512, 64, -280), 320, "coast_spot_b$tag")
	}

	// ---------------------------------------------------------------- measurement

	private fun measure(level: ServerLevel, out: StringBuilder) {
		val gen = level.chunkSource.generator
		val rs = level.chunkSource.randomState()
		val sea = gen.seaLevel
		val t0 = System.nanoTime()
		var shelfSum = 0.0
		var beachSum = 0.0
		var found = 0
		for (t in TRANSECTS) {
			val n = max(abs(t[3] - t[1]), abs(t[4] - t[2]))
			val floor = IntArray(n + 1) { i ->
				val x = t[1] + ((t[3] - t[1]) * i.toFloat() / n).roundToInt()
				val z = t[2] + ((t[4] - t[2]) * i.toFloat() / n).roundToInt()
				gen.getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, rs) - 1
			}
			var shore = -1
			for (i in 0..n - 2) {
				if (floor[i] >= sea - 1 && floor[i + 1] >= sea - 1 && floor[i + 2] >= sea - 1) {
					shore = i
					break
				}
			}
			if (shore <= 0) {
				out.append("\n  transect ${t[0]}: no shoreline")
				continue
			}
			var shelf5 = 0
			var shelf10 = 0
			var i = shore - 1
			while (i >= 0 && floor[i] < sea - 1 && sea - 1 - floor[i] <= 5) {
				shelf5++
				i--
			}
			i = shore - 1
			while (i >= 0 && floor[i] < sea - 1 && sea - 1 - floor[i] <= 10) {
				shelf10++
				i--
			}
			var beach = 0
			i = shore
			while (i <= n && floor[i] >= sea - 1 && floor[i] <= sea + 2) {
				beach++
				i++
			}
			val sx = t[1] + ((t[3] - t[1]) * shore.toFloat() / n).roundToInt()
			val sz = t[2] + ((t[4] - t[2]) * shore.toFloat() / n).roundToInt()
			out.append("\n  transect %d: shoreline at (%d, %d); shelf<=5 deep %d, shelf<=10 deep %d, low beach (<=3 up) %d, total %d".format(
				t[0], sx, sz, shelf5, shelf10, beach, shelf5 + beach))
			shelfSum += shelf5
			beachSum += beach
			found++
		}
		out.append("\n  transect mean: shelf<=5 %.1f, beach %.1f, total %.1f (n=%d)".format(
			shelfSum / found, beachSum / found, (shelfSum + beachSum) / found, found))

		// area statistics: shallow-water and low-land area per unit coastline length
		val size = 1536
		val stride = 8
		val n = size / stride
		val cx = 0
		val cz = -256
		val fl = Array(n) { ix -> IntArray(n) { iz ->
			gen.getBaseHeight(cx - size / 2 + ix * stride, cz - size / 2 + iz * stride, Heightmap.Types.OCEAN_FLOOR_WG, level, rs) - 1
		} }
		var edges = 0L
		var shallow = 0L
		var low = 0L
		var water = 0L
		for (iz in 0 until n) {
			for (ix in 0 until n) {
				val f = fl[ix][iz]
				val wet = f < sea - 1
				if (wet) {
					water++
					if (sea - 1 - f <= 5) {
						shallow++
					}
				} else if (f <= sea + 2) {
					low++
				}
				if (ix + 1 < n && wet != (fl[ix + 1][iz] < sea - 1)) {
					edges++
				}
				if (iz + 1 < n && wet != (fl[ix][iz + 1] < sea - 1)) {
					edges++
				}
			}
		}
		val coastLength = edges * Math.PI * stride / 4.0
		val shallowArea = shallow * stride.toDouble() * stride
		val lowArea = low * stride.toDouble() * stride
		out.append(("\n  area %dx%d at (%d,%d) stride %d: water %.1f%%, coastline ~%.0f blocks, shallow(<=5) %.0f m2 -> %.1f blocks per coast " +
			"block, low land(<=3 up) %.0f m2 -> %.1f blocks per coast block").format(size, size, cx, cz, stride, 100.0 * water / (n * n),
			coastLength, shallowArea, shallowArea / coastLength, lowArea, lowArea / coastLength))
		out.append("\n  (measured in ${(System.nanoTime() - t0) / 1_000_000} ms)")
	}

	// ---------------------------------------------------------------- io

	private fun tag(): String = try {
		val p = Path.of("coast_tag.txt")
		if (Files.exists(p)) Files.readString(p).trim() else ""
	} catch (e: IOException) {
		""
	}

	private fun writeText(name: String, text: String) {
		try {
			val dir = Path.of("renders")
			Files.createDirectories(dir)
			Files.writeString(dir.resolve("$name.txt"), text)
		} catch (e: IOException) {
			BiomesMod.LOGGER.error("[coast] failed to write {}", name, e)
		}
	}
}
