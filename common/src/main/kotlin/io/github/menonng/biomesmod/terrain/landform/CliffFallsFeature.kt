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
 * `biomesmod:cliff_falls`: real waterfalls found on real cliffs. The feature looks for a plateau edge with a sheer drop
 * of at least `min_drop` blocks, cuts a stream into the plateau that runs to the lip, and pours it over: the falling
 * column is laid down the face to wherever it lands (the fjord, a ledge, the valley floor), where a plunge pool is dug.
 * Several falls can stand side by side along the same edge (`cluster`, the Seven Sisters). Every water block rests on
 * or flows from terrain; nothing is left hanging in the air, and fluid ticks are scheduled so the falls stay live.
 * Place once per chunk at the surface (count 1, heightmap).
 */
class CliffFallsFeature : Feature<CliffFallsFeature.Config>(Config.CODEC) {
	data class Config(val tries: Int, val minDrop: Int, val stream: Span, val cluster: Int, val chance: Float) : FeatureConfiguration {
		companion object {
			@JvmField
			val CODEC: Codec<Config> = RecordCodecBuilder.create { i ->
				i.group(
					Codec.intRange(1, 64).optionalFieldOf("tries", 24).forGetter(Config::tries),
					Codec.intRange(6, 200).optionalFieldOf("min_drop", 18).forGetter(Config::minDrop),
					Span.CODEC.optionalFieldOf("stream", Span.of(4.0, 11.0)).forGetter(Config::stream),
					Codec.intRange(1, 8).optionalFieldOf("cluster", 1).forGetter(Config::cluster),
					Codec.floatRange(0f, 1f).optionalFieldOf("chance", 0.35f).forGetter(Config::chance),
				).apply(i) { tries, minDrop, stream, cluster, chance -> Config(tries, minDrop, stream, cluster, chance) }
			}
		}
	}

	private val water: BlockState get() = Blocks.WATER.defaultBlockState()
	private val falling: BlockState get() = Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL, 8)

	override fun place(context: FeaturePlaceContext<Config>): Boolean {
		val level = context.level()
		val r = context.random()
		val cfg = context.config()
		if (r.nextFloat() >= cfg.chance) {
			return false
		}
		val o = context.origin()
		val x0 = o.x and 15.inv()
		val z0 = o.z and 15.inv()
		val cx = x0 shr 4
		val cz = z0 shr 4
		for (t in 0 until cfg.tries) {
			val x = x0 + 2 + r.nextInt(12)
			val z = z0 + 2 + r.nextInt(12)
			val top = ground(level, x, z) ?: continue
			for (dir in Direction.Plane.HORIZONTAL.shuffledCopy(r)) {
				val edge = findEdge(level, x, top, z, dir, cfg.minDrop) ?: continue
				var placed = 0
				val side = dir.clockWise
				for (k in 0 until cfg.cluster) {
					val off = if (k == 0) 0 else (k + 1) / 2 * (3 + r.nextInt(4)) * (if (k % 2 == 0) 1 else -1)
					val lx = edge.x + side.stepX * off
					val lz = edge.z + side.stepZ * off
					if (!inside(cx, cz, lx, lz, 3)) continue
					val lt = ground(level, lx, lz) ?: continue
					if (findEdge(level, lx, lt, lz, dir, cfg.minDrop, 0) == null) continue
					val len = (cfg.stream.min + r.nextFloat() * (cfg.stream.max - cfg.stream.min)).toInt() / (if (k == 0) 1 else 2)
					if (fall(level, cx, cz, lx, lt, lz, dir, len)) placed++
				}
				if (placed > 0) return true
			}
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

	/** The lip column (on the plateau) if stepping in [dir] from (x, z) drops at least [minDrop] within [reach] blocks. */
	private fun findEdge(level: WorldGenLevel, x: Int, top: Int, z: Int, dir: Direction, minDrop: Int, reach: Int = 3): BlockPos? {
		var lx = x
		var lz = z
		var lt = top
		for (s in 0..reach) {
			val nx = lx + dir.stepX
			val nz = lz + dir.stepZ
			val p = BlockPos.MutableBlockPos(nx, lt, nz)
			// The next column must be open at the lip height and fall away steeply.
			if (!level.getBlockState(p).isAir) {
				val nt = ground(level, nx, nz) ?: return null
				if (nt > lt + 1) return null
				lx = nx
				lz = nz
				lt = nt
				continue
			}
			var y = lt
			while (y > lt - minDrop - 80 && level.getBlockState(p.set(nx, y - 1, nz)).isAir) y--
			return if (lt - y >= minDrop) BlockPos(lx, lt, lz) else null
		}
		return null
	}

	private fun inside(cx: Int, cz: Int, x: Int, z: Int, margin: Int): Boolean =
		(x - margin) shr 4 >= cx - 1 && (x + margin) shr 4 <= cx + 1 && (z - margin) shr 4 >= cz - 1 && (z + margin) shr 4 <= cz + 1

	private fun fall(level: WorldGenLevel, cx: Int, cz: Int, lx: Int, lt: Int, lz: Int, dir: Direction, len: Int): Boolean {
		val p = BlockPos.MutableBlockPos()
		// Stream: cut back from the lip into the plateau, following the ground, walled by terrain on both sides.
		var placed = 0
		for (s in len downTo 0) {
			val x = lx - dir.stepX * s
			val z = lz - dir.stepZ * s
			if (!inside(cx, cz, x, z, 1)) continue
			val y = ground(level, x, z) ?: continue
			if (y > lt + 2 || y < lt - 1) continue
			// Make sure both banks and the bed hold the water.
			val side = dir.clockWise
			val bed = level.getBlockState(p.set(x, y - 1, z))
			if (!ChunkWriter.isTerrain(bed)) continue
			for (b in arrayOf(side, side.opposite)) {
				val bank = p.set(x + b.stepX, y, z + b.stepZ)
				if (!ChunkWriter.isTerrain(level.getBlockState(bank))) level.setBlock(bank, Blocks.STONE.defaultBlockState(), 2)
			}
			clearAbove(level, x, y, z)
			level.setBlock(p.set(x, y, z), water, 2)
			level.scheduleTick(p.immutable(), Fluids.WATER, 1)
			placed++
		}
		if (placed == 0) return false
		// The falling column down the face, from the open block beyond the lip to the first solid block.
		val fx = lx + dir.stepX
		val fz = lz + dir.stepZ
		var y = ground(level, lx, lz) ?: lt
		var landed = Int.MIN_VALUE
		while (y > io.github.menonng.biomesmod.compat.LevelCompat.minY(level) + 1) {
			val s = level.getBlockState(p.set(fx, y, fz))
			if (!s.isAir && !s.`is`(BlockTags.LEAVES) && !s.canBeReplaced()) {
				landed = if (s.fluidState.isEmpty) y else Int.MIN_VALUE + 1
				break
			}
			level.setBlock(p, falling, 2)
			y--
		}
		level.scheduleTick(BlockPos(fx, ground(level, lx, lz) ?: lt, fz), Fluids.WATER, 1)
		// A plunge pool where it hits ground (falls into water need none).
		if (landed > Int.MIN_VALUE + 1) {
			for (dz in -1..1) for (dx in -1..1) {
				val px = fx + dx
				val pz = fz + dz
				if (!inside(cx, cz, px, pz, 0)) continue
				val g = level.getBlockState(p.set(px, landed, pz))
				if (ChunkWriter.isTerrain(g) && ChunkWriter.isTerrain(level.getBlockState(p.set(px, landed - 1, pz)))) {
					var walled = true
					for (d in Direction.Plane.HORIZONTAL) {
						val n = level.getBlockState(p.set(px + d.stepX, landed, pz + d.stepZ))
						if (n.isAir && (Math.abs(dx + d.stepX) > 1 || Math.abs(dz + d.stepZ) > 1)) walled = false
					}
					if (walled) level.setBlock(p.set(px, landed, pz), water, 2)
				}
			}
		}
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
