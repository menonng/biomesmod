package io.github.menonng.biomesmod.terrain.landform

import io.github.menonng.biomesmod.terrain.util.StrataPalette
import net.minecraft.world.level.block.state.BlockState
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * One placed landform instance. Built once per grid cell (cached), then asked by every chunk it overlaps to write that
 * chunk's part. [column] must be a pure function of (instance, x, z) so chunk borders agree.
 */
abstract class Landform internal constructor(s: Site, reach: Int) {
	internal val config: LandformConfig = s.config
	internal val cx: Int = s.x
	internal val cz: Int = s.z
	internal val reach: Int = min(reach, s.config.shape.maxReach())
	internal val seed: Long = s.seed
	internal val sea: Int = s.sea
	internal val minY: Int = s.minY
	internal val maxY: Int = s.maxY
	internal val palette: StrataPalette = s.config.palette()
	internal val talus: List<BlockState> = s.config.talus()
	internal val surface: List<BlockState> = s.config.surfaceBlocks
	internal val cap: Cap? = s.config.cap().orElse(null)

	/** Holds water: evaluate a one-column border around each chunk and seal basin walls. */
	internal var holdsWater = false

	/** Pure per-column geometry. */
	internal abstract fun column(x: Int, z: Int, c: Col)

	fun centerX(): Int = cx

	fun centerZ(): Int = cz

	/** Max horizontal distance from the centre this instance writes to. */
	fun reach(): Int = reach

	/** A short description for logs (key parameters of this instance). */
	open fun describe(): String = "${javaClass.simpleName} at $cx,$cz"

	internal fun overlaps(x0: Int, z0: Int, x1: Int, z1: Int): Boolean =
		cx + reach >= x0 && cx - reach <= x1 && cz + reach >= z0 && cz - reach <= z1

	/** Writes this instance's part of the writer's chunk. */
	internal fun write(w: ChunkWriter) {
		val b = if (holdsWater) 1 else 0
		val xa = max(w.x0 - b, cx - reach)
		val xb = min(w.x0 + 15 + b, cx + reach)
		val za = max(w.z0 - b, cz - reach)
		val zb = min(w.z0 + 15 + b, cz + reach)
		if (xa > xb || za > zb) {
			return
		}
		val grid = arrayOfNulls<Col>(18 * 18)
		for (z in za..zb) {
			for (x in xa..xb) {
				val c = Col()
				column(x, z, c)
				grid[ChunkWriter.gridIndex(x - w.x0, z - w.z0)] = c
			}
		}
		for (dz in 0 until 16) {
			for (dx in 0 until 16) {
				val c = grid[ChunkWriter.gridIndex(dx, dz)] ?: continue
				w.apply(this, w.x0 + dx, w.z0 + dz, c)
			}
		}
		if (holdsWater) {
			seal(w, grid)
		}
		decorate(w, grid)
	}

	/** Every dry column next to a water column of this instance gets its wall made solid up to that water level. */
	private fun seal(w: ChunkWriter, grid: Array<Col?>) {
		for (dz in 0 until 16) {
			for (dx in 0 until 16) {
				val c = grid[ChunkWriter.gridIndex(dx, dz)]
				if (c != null && (c.isWater() || (c.flag and Col.FLAG_NO_SEAL) != 0)) {
					continue
				}
				var need = Col.NONE
				var low = Int.MAX_VALUE
				for (k in 0 until 4) {
					val nx = dx + (if (k == 0) 1 else if (k == 1) -1 else 0)
					val nz = dz + (if (k == 2) 1 else if (k == 3) -1 else 0)
					val n = grid[ChunkWriter.gridIndex(nx, nz)]
					if (n != null && n.isWater()) {
						need = max(need, n.water)
						low = min(low, n.top + 1)
					}
				}
				if (need != Col.NONE) {
					w.seal(this, w.x0 + dx, w.z0 + dz, low, need)
				}
			}
		}
	}

	/** Material of a block that is [depth] blocks below the column's new top. */
	internal open fun block(x: Int, y: Int, z: Int, depth: Int, c: Col, wet: Boolean): BlockState =
		if (c.kind == K_TALUS) talusBlock(x, y, z) else strata(x, y, z, depth, wet)

	/** Extra blocks after all columns are written (trees, falls, vents ...). Writes only inside the chunk. */
	internal open fun decorate(w: ChunkWriter, grid: Array<Col?>) {
		placeCapFeatures(w, grid)
	}

	// ---- material helpers ----

	internal fun strata(x: Int, y: Int, z: Int, depth: Int, wet: Boolean): BlockState {
		if (depth == 0 && !wet && surface.isNotEmpty()) {
			return pick(surface, x, y, z, 0x5F1)
		}
		return if (wet) palette.strataAt(y) else palette.blockAt(y, depth)
	}

	internal fun talusBlock(x: Int, y: Int, z: Int): BlockState = pick(talus, x, y, z, 0x7A1)

	/** Patchy choice from a list: clustered by low-frequency noise, speckled by a per-block hash. */
	internal fun pick(list: List<BlockState>, x: Int, y: Int, z: Int, salt: Long): BlockState {
		val n = list.size
		if (n == 1) {
			return list.first()
		}
		val v = 0.5 + 0.42 * LNoise.noise2(seed xor salt, x / 6.5, z / 6.5) + 0.35 * (LNoise.unit(seed xor salt, x, y, z) - 0.5)
		val i = floor(v * n).toInt()
		return list[max(0, min(n - 1, i))]
	}

	internal open fun sealBlock(x: Int, y: Int, z: Int): BlockState = palette.strataAt(y)

	internal fun placeCapFeatures(w: ChunkWriter, grid: Array<Col?>) {
		val cap = cap ?: return
		if (cap.feature.isEmpty || cap.chance <= 0) {
			return
		}
		for (dz in 0 until 16) {
			for (dx in 0 until 16) {
				val c = grid[ChunkWriter.gridIndex(dx, dz)]
				if (c == null || !c.cap) {
					continue
				}
				val x = w.x0 + dx
				val z = w.z0 + dz
				val h = LNoise.hash(seed xor 0x7EE5, x, z)
				if (LNoise.unit(h) < cap.chance) {
					val y = w.top(x, z)
					if (y >= sea) {
						w.placeFeature(cap.feature.get(), x, y + 1, z, h)
					}
				}
			}
		}
	}

	/**
	 * Top of an eroded vertical wall at normalised distance [q] (1 = nominal outline): solid at y while
	 * q <= 1 + band * n(y) - taper * t(y). Scanning from the bottom and stopping at the first miss keeps walls free of
	 * floating overhangs; horizontal bands give ledges and notches like real strata.
	 */
	internal fun erodedTop(q: Double, y0: Int, y1: Int, band: Double, taper: Double, bandSeed: Long, bandScale: Double): Int {
		if (q <= 1 - band - taper) {
			return y1
		}
		if (q > 1 + band) {
			return y0 - 1
		}
		val h = max(1, y1 - y0)
		for (y in y0..y1) {
			val t = (y - y0) / h.toDouble()
			val lim = 1 + band * LNoise.noise1(bandSeed, y / bandScale) - taper * t
			if (q > lim) {
				return y - 1
			}
		}
		return y1
	}

	companion object {
		internal const val K_BODY = 0
		internal const val K_TALUS = 1
		internal const val K_PEDESTAL = 2
		internal const val K_FLOOR = 3
		internal const val K_SEAL = 9
	}
}
