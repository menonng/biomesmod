package io.github.menonng.biomesmod.terrain.landform

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration
import net.minecraft.world.level.material.Fluids

/**
 * `biomesmod:cliff_falls`: real waterfalls found on real walls. The feature looks for a steep face (a fjord wall, a
 * trough side, a plateau edge) that drops at least `min_drop` blocks at a mean gradient of at least `min_slope` blocks
 * per column. The face does not have to be vertical: the lip is the break of slope at its top, found by climbing the
 * face from a random point. A stream is traced uphill from the lip to an upland source (stepping always to the gentlest
 * rise) and laid down so every step is a small cascade; down the face the water runs in a one-block groove, falling
 * freely where the wall is sheer and stepping from ledge to ledge where it is not, to wherever it lands (the fjord, a
 * ledge, the valley floor), where a plunge pool is dug. Several falls can stand side by side along the same wall
 * (`cluster`, the Seven Sisters). Every water block rests on or flows from terrain; nothing is left hanging in the air,
 * and fluid ticks are scheduled so the falls stay live. Place once per chunk at the surface (count 1, heightmap).
 */
class CliffFallsFeature : Feature<CliffFallsFeature.Config>(Config.CODEC) {
	data class Config(val tries: Int, val minDrop: Int, val stream: Span, val cluster: Int, val chance: Float, val minSlope: Float) : FeatureConfiguration {
		companion object {
			@JvmField
			val CODEC: Codec<Config> = RecordCodecBuilder.create { i ->
				i.group(
					Codec.intRange(1, 64).optionalFieldOf("tries", 24).forGetter(Config::tries),
					Codec.intRange(6, 200).optionalFieldOf("min_drop", 18).forGetter(Config::minDrop),
					Span.CODEC.optionalFieldOf("stream", Span.of(4.0, 11.0)).forGetter(Config::stream),
					Codec.intRange(1, 8).optionalFieldOf("cluster", 1).forGetter(Config::cluster),
					Codec.floatRange(0f, 1f).optionalFieldOf("chance", 0.35f).forGetter(Config::chance),
					Codec.floatRange(0.3f, 8f).optionalFieldOf("min_slope", 1.0f).forGetter(Config::minSlope),
				).apply(i) { tries, minDrop, stream, cluster, chance, minSlope -> Config(tries, minDrop, stream, cluster, chance, minSlope) }
			}
		}
	}

	companion object {
		/** Falls placed since start (reported by the self-check). */
		@JvmField
		val PLACED = java.util.concurrent.atomic.AtomicInteger()

		/** Why candidates were dropped: 0 chunks tried, 1 no slope at the point, 2 face too low, 3 face too gentle, 4 no stream above the lip, 5 placed. */
		@JvmField
		val STATS = java.util.concurrent.atomic.AtomicIntegerArray(6)

		private const val MAX_RUN = 40
	}

	/** A face: the lip column and the columns below it in order, y being the ground (or the water surface in the last one if [intoWater]). */
	private class Face(val lx: Int, val lt: Int, val lz: Int, val cols: List<IntArray>, val intoWater: Boolean)

	private val water: BlockState get() = Blocks.WATER.defaultBlockState()
	private val falling: BlockState get() = Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL, 8)
	private val running: BlockState get() = Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL, 1)

	override fun place(context: FeaturePlaceContext<Config>): Boolean {
		val level = context.level()
		val r = context.random()
		val cfg = context.config()
		if (r.nextFloat() >= cfg.chance) {
			return false
		}
		STATS.incrementAndGet(0)
		val o = context.origin()
		val x0 = o.x and 15.inv()
		val z0 = o.z and 15.inv()
		val cx = x0 shr 4
		val cz = z0 shr 4
		for (t in 0 until cfg.tries) {
			val x = x0 + 2 + r.nextInt(12)
			val z = z0 + 2 + r.nextInt(12)
			val top = ground(level, x, z) ?: continue
			// Only the direction of steepest descent from here is worth walking.
			var dir: Direction? = null
			var steep = 3
			for (d in Direction.Plane.HORIZONTAL) {
				val g = ground(level, x + d.stepX * 5, z + d.stepZ * 5) ?: waterTop(level, x + d.stepX * 5, z + d.stepZ * 5) ?: continue
				if (top - g > steep) {
					steep = top - g
					dir = d
				}
			}
			if (dir == null) {
				STATS.incrementAndGet(1)
				continue
			}
			val face = findFace(level, cx, cz, x, top, z, dir, cfg) ?: continue
			var placed = 0
			val side = dir.clockWise
			for (k in 0 until cfg.cluster) {
				var f = face
				if (k > 0) {
					val off = (k + 1) / 2 * (3 + r.nextInt(4)) * (if (k % 2 == 0) 1 else -1)
					val sx = face.lx + side.stepX * off
					val sz = face.lz + side.stepZ * off
					if (!inside(cx, cz, sx, sz, 3)) continue
					val st = ground(level, sx, sz) ?: continue
					f = findFace(level, cx, cz, sx, st, sz, dir, cfg, false) ?: continue
				}
				val len = (cfg.stream.min + r.nextFloat() * (cfg.stream.max - cfg.stream.min)).toInt() / (if (k == 0) 1 else 2)
				if (fall(level, cx, cz, f, dir, len)) {
					placed++
					PLACED.incrementAndGet()
					STATS.incrementAndGet(5)
				} else if (k == 0) {
					STATS.incrementAndGet(4)
				}
			}
			if (placed > 0) return true
		}
		return false
	}

	/** Surface y of solid ground (not leaves, not water) at x, z, or null over water. */
	private fun ground(level: WorldGenLevel, x: Int, z: Int): Int? {
		val p = BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z), z)
		var guard = 0
		while (guard++ < 60) {
			val s = level.getBlockState(p)
			if (!s.fluidState.isEmpty) return null
			if (ChunkWriter.isTerrain(s)) return p.y
			p.move(Direction.DOWN)
		}
		return null
	}

	/** Y of the top water block at x, z, or null if the column is not open water. */
	private fun waterTop(level: WorldGenLevel, x: Int, z: Int): Int? {
		val p = BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z), z)
		var guard = 0
		while (guard++ < 60) {
			val s = level.getBlockState(p)
			if (!s.fluidState.isEmpty) return p.y
			if (ChunkWriter.isTerrain(s)) return null
			p.move(Direction.DOWN)
		}
		return null
	}

	/**
	 * The face that (x, z) stands on or above, looking downhill in [dir]: climbs to the break of slope at the top (the
	 * lip), then walks down column by column until the wall ends at water, at a flat, at a rise, or at the edge of the
	 * writable region. Null if what it finds is lower than `min_drop` or gentler than `min_slope`.
	 */
	private fun findFace(level: WorldGenLevel, cx: Int, cz: Int, x: Int, top: Int, z: Int, dir: Direction, cfg: Config, count: Boolean = true): Face? {
		var lx = x
		var lz = z
		var lt = top
		// Climb while the wall keeps rising steeply behind us.
		var climbed = 0
		while (climbed < MAX_RUN) {
			val nx = lx - dir.stepX
			val nz = lz - dir.stepZ
			if (!inside(cx, cz, nx, nz, 4)) break
			val nt = ground(level, nx, nz) ?: break
			if (nt - lt < 2) break
			lx = nx
			lz = nz
			lt = nt
			climbed++
		}
		// On a shelf above the wall: walk out to where it breaks away.
		if (climbed == 0) {
			var s = 0
			while (s < 8) {
				val nx = lx + dir.stepX
				val nz = lz + dir.stepZ
				val nt = ground(level, nx, nz) ?: break
				if (lt - nt >= 2) break
				if (nt > lt + 1 || !inside(cx, cz, nx, nz, 4)) return null
				lx = nx
				lz = nz
				lt = nt
				s++
			}
		}
		val cols = ArrayList<IntArray>()
		var px = lx
		var pz = lz
		var ph = lt
		var intoWater = false
		while (cols.size < MAX_RUN) {
			val nx = px + dir.stepX
			val nz = pz + dir.stepZ
			if (!inside(cx, cz, nx, nz, 2)) break
			val g = ground(level, nx, nz)
			if (g == null) {
				val w = waterTop(level, nx, nz) ?: break
				if (w > ph) break
				cols.add(intArrayOf(nx, w, nz))
				intoWater = true
				break
			}
			if (g > ph) break
			cols.add(intArrayOf(nx, g, nz))
			// The foot of the wall: no more than a block of fall over the last four columns.
			if (cols.size >= 4 && cols[cols.size - 4][1] - g <= 1) {
				repeat(3) { cols.removeAt(cols.size - 1) }
				break
			}
			px = nx
			pz = nz
			ph = g
		}
		if (cols.isEmpty()) return null
		val drop = lt - cols[cols.size - 1][1]
		if (drop < cfg.minDrop) {
			if (count) STATS.incrementAndGet(2)
			return null
		}
		if (drop.toFloat() / cols.size < cfg.minSlope) {
			if (count) STATS.incrementAndGet(3)
			return null
		}
		return Face(lx, lt, lz, cols, intoWater)
	}

	private fun inside(cx: Int, cz: Int, x: Int, z: Int, margin: Int): Boolean =
		(x - margin) shr 4 >= cx - 1 && (x + margin) shr 4 <= cx + 1 && (z - margin) shr 4 >= cz - 1 && (z + margin) shr 4 <= cz + 1

	/**
	 * One waterfall, built the way a real stream reaches a wall: the channel is traced uphill from the lip, always
	 * stepping to the neighbouring column that rises least, to a source on the upland; water is laid along it from the
	 * source down, so every drop on the way is a small cascade. Over the lip it goes down the face in a groove one block
	 * deep (banked where the wall falls away sideways), falling free over every sheer stretch; where it lands a plunge
	 * pool is dug and the stream carries on downhill until it reaches standing water or runs out.
	 */
	private fun fall(level: WorldGenLevel, cx: Int, cz: Int, face: Face, dir: Direction, len: Int): Boolean {
		val p = BlockPos.MutableBlockPos()
		val lx = face.lx
		val lt = face.lt
		val lz = face.lz
		// 1) Trace upstream from the lip.
		val path = ArrayList<IntArray>()
		path.add(intArrayOf(lx, lt, lz))
		var x = lx
		var z = lz
		var y = lt
		val seen = HashSet<Long>()
		seen.add(BlockPos.asLong(x, 0, z))
		for (step in 0 until len) {
			var best: IntArray? = null
			for (d in Direction.Plane.HORIZONTAL) {
				if (d == dir) continue // never back towards the wall
				val nx = x + d.stepX
				val nz = z + d.stepZ
				if (!inside(cx, cz, nx, nz, 1) || seen.contains(BlockPos.asLong(nx, 0, nz))) continue
				val ny = ground(level, nx, nz) ?: continue
				if (ny < y || ny > y + 3) continue // streams only climb gently when traced upstream
				if (best == null || ny < best[1] || (ny == best[1] && d == dir.opposite)) best = intArrayOf(nx, ny, nz)
			}
			val nb = best ?: break
			seen.add(BlockPos.asLong(nb[0], 0, nb[2]))
			path.add(nb)
			x = nb[0]
			y = nb[1]
			z = nb[2]
		}
		if (path.size < 3) return false
		// 2) Lay the stream from the source down to the lip.
		for (i in path.indices.reversed()) {
			val c = path[i]
			val py = if (i + 1 < path.size) path[i + 1][1] else c[1]
			val down = if (i > 0) path[i - 1] else face.cols[0]
			if (!channel(level, c[0], c[1], c[2], true, down[0], down[2])) return false
			// Where the bed steps down the water above falls into this column.
			for (yy in c[1] + 1..py) setWater(level, p.set(c[0], yy, c[2]), if (yy == py) running else falling)
		}
		// 3) Down the face.
		var prev = lt
		val side = dir.clockWise
		val last = face.cols.size - 1
		for ((i, c) in face.cols.withIndex()) {
			val h = c[1]
			if (i == last && face.intoWater) {
				for (yy in prev downTo h + 1) setWater(level, p.set(c[0], yy, c[2]), if (yy == prev) running else falling)
				level.scheduleTick(BlockPos(c[0], prev, c[2]), Fluids.WATER, 1)
				return true
			}
			// The water cuts a notch: its level here is no higher than the ground on either side, so the wall itself
			// holds it in (a stream incises its bed; nothing is banked up above the slope).
			var w = minOf(h, prev)
			for (sgn in intArrayOf(-1, 1)) {
				var by = w
				while (by > h - 6 && !ChunkWriter.isTerrain(level.getBlockState(p.set(c[0] + side.stepX * sgn, by, c[2] + side.stepZ * sgn)))) by--
				w = minOf(w, by)
			}
			if (!ChunkWriter.isTerrain(level.getBlockState(p.set(c[0], w - 1, c[2])))) level.setBlock(p, Blocks.STONE.defaultBlockState(), 2)
			for (yy in h downTo w + 1) level.setBlock(p.set(c[0], yy, c[2]), Blocks.AIR.defaultBlockState(), 2)
			clearAbove(level, c[0], h, c[2])
			for (yy in prev downTo w + 1) setWater(level, p.set(c[0], yy, c[2]), if (yy == prev) running else falling)
			level.setBlock(p.set(c[0], w, c[2]), water, 2)
			level.scheduleTick(p.immutable(), Fluids.WATER, 1)
			prev = w
		}
		// 4) Plunge pool and the stream on below the falls, down to standing water.
		val fx = face.cols[last][0]
		val fz = face.cols[last][2]
		val landed = prev
		for (dz in -1..1) for (dx in -1..1) {
			if ((dx != 0 || dz != 0) && inside(cx, cz, fx + dx, fz + dz, 1)) {
				val g = ground(level, fx + dx, fz + dz) ?: continue
				if (g != landed && g != landed + 1) continue
				if (g == landed + 1) level.setBlock(p.set(fx + dx, g, fz + dz), Blocks.AIR.defaultBlockState(), 2)
				channel(level, fx + dx, landed, fz + dz, false, 0, 0)
			}
		}
		x = fx
		z = fz
		y = landed
		for (step in 0 until 14) {
			var best: IntArray? = null
			for (d in Direction.Plane.HORIZONTAL) {
				val nx = x + d.stepX
				val nz = z + d.stepZ
				if (!inside(cx, cz, nx, nz, 1)) continue
				val top = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, nx, nz) - 1
				if (!level.getBlockState(p.set(nx, top, nz)).fluidState.isEmpty && top <= y) return true // reached water
				val ny = ground(level, nx, nz) ?: continue
				if (ny > y) continue
				if (best == null || ny < best[1]) best = intArrayOf(nx, ny, nz)
			}
			val nb = best ?: break
			for (yy in nb[1] + 1..y) setWater(level, p.set(nb[0], yy, nb[2]), if (yy == y) running else falling)
			channel(level, nb[0], nb[1], nb[2], false, 0, 0)
			x = nb[0]
			y = nb[1]
			z = nb[2]
		}
		return true
	}

	/** Puts flowing water into an open (air, plant, snow layer, leaf) block; never replaces terrain or a source. */
	private fun setWater(level: WorldGenLevel, p: BlockPos, state: BlockState) {
		val s = level.getBlockState(p)
		if (!s.fluidState.isEmpty || ChunkWriter.isTerrain(s)) return
		level.setBlock(p, state, 2)
	}

	/** Turns the ground block at (x, y, z) into a water source on a solid bed, with banks if [bank] (not towards downX, downZ). */
	private fun channel(level: WorldGenLevel, x: Int, y: Int, z: Int, bank: Boolean, downX: Int, downZ: Int): Boolean {
		val p = BlockPos.MutableBlockPos()
		if (!ChunkWriter.isTerrain(level.getBlockState(p.set(x, y - 1, z)))) return false
		val fill = level.getBlockState(p)
		for (b in Direction.Plane.HORIZONTAL) {
			if (!bank) break
			// Open side (other than downstream): bank it up to the water level so the stream stays in its bed.
			val bx = x + b.stepX
			val bz = z + b.stepZ
			if (bx == downX && bz == downZ) continue
			val st = level.getBlockState(p.set(bx, y, bz))
			if (!st.fluidState.isEmpty || ChunkWriter.isTerrain(st)) continue
			var by = y
			while (by > y - 3 && !ChunkWriter.isTerrain(level.getBlockState(p.set(bx, by, bz))) && level.getBlockState(p).fluidState.isEmpty) by--
			if (by > y - 3 && level.getBlockState(p.set(bx, by, bz)).fluidState.isEmpty) for (yy in by + 1..y) level.setBlock(p.set(bx, yy, bz), fill, 2)
		}
		clearAbove(level, x, y, z)
		level.setBlock(p.set(x, y, z), water, 2)
		level.scheduleTick(p.immutable(), Fluids.WATER, 1)
		return true
	}

	private fun clearAbove(level: WorldGenLevel, x: Int, y: Int, z: Int) {
		val p = BlockPos.MutableBlockPos(x, y + 1, z)
		for (k in 0 until 3) {
			val s = level.getBlockState(p)
			if (!s.isAir && (s.canBeReplaced() || s.`is`(BlockTags.FLOWERS))) level.setBlock(p, Blocks.AIR.defaultBlockState(), 2)
			p.move(Direction.UP)
		}
	}
}
