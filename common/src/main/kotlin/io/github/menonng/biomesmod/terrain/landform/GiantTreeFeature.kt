package io.github.menonng.biomesmod.terrain.landform

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.tags.BlockTags
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.AmethystClusterBlock
import net.minecraft.world.level.block.LeavesBlock
import net.minecraft.world.level.block.RotatedPillarBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration
import java.util.Optional
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `biomesmod:giant_tree`: trees far bigger than vanilla's, built from true 3D trunks and limbs.
 *
 * - `spire`: a coast redwood / sequoia. A thick round trunk (up to 5 blocks across) flaring into buttress roots, rising
 *   50 to 75 blocks with a slow taper; short drooping branches start at mid height, each with a flat spray of foliage,
 *   and the crown closes into a narrow cone at the top.
 * - `broad`: a veteran broadleaf. A short massive trunk on spreading surface roots splits at mid height into a few thick
 *   limbs that fork again and again, carrying big leaf clouds into a wide, uneven dome; now and then a limb is dead and
 *   bare.
 *
 * Logs are oriented along each limb; leaves are persistent. Grows on dirt-like ground and never overwrites terrain.
 */
class GiantTreeFeature : Feature<GiantTreeFeature.Config>(Config.CODEC) {
	data class Config(
		val style: String, val log: BlockState, val wood: BlockState, val leaves: BlockState, val height: Span, val radius: Span,
		val crown: Span, val deadLimbs: Float, val dress: Dress,
	) : FeatureConfiguration {
		companion object {
			@JvmField
			val CODEC: Codec<Config> = RecordCodecBuilder.create { i ->
				i.group(
					Codec.STRING.optionalFieldOf("style", "broad").forGetter(Config::style),
					BlockState.CODEC.fieldOf("log").forGetter(Config::log),
					BlockState.CODEC.fieldOf("wood").forGetter(Config::wood),
					BlockState.CODEC.fieldOf("leaves").forGetter(Config::leaves),
					Span.CODEC.optionalFieldOf("height", Span.of(22.0, 32.0)).forGetter(Config::height),
					Span.CODEC.optionalFieldOf("radius", Span.of(1.2, 2.0)).forGetter(Config::radius),
					Span.CODEC.optionalFieldOf("crown", Span.of(8.0, 13.0)).forGetter(Config::crown),
					Codec.floatRange(0f, 1f).optionalFieldOf("dead_limbs", 0.1f).forGetter(Config::deadLimbs),
					Dress.CODEC.optionalFieldOf("dress", Dress.NONE).forGetter(Config::dress),
				).apply(i) { style, log, wood, leaves, height, radius, crown, dead, dress ->
					Config(style, log, wood, leaves, height, radius, crown, dead, dress)
				}
			}
		}
	}

	/**
	 * Optional dressing: `hanging` (lichen strands under the leaves), `bracket` (shelf fungus on the trunk, facing out),
	 * `hollow` (one hollow trunk block), `carpet` (moss on top of roots), each with a chance.
	 */
	data class Dress(
		val hanging: Optional<BlockState>, val hangingChance: Float, val bracket: Optional<BlockState>, val bracketChance: Float,
		val hollow: Optional<BlockState>, val hollowChance: Float, val carpet: Optional<BlockState>, val carpetChance: Float,
	) {
		companion object {
			val NONE = Dress(Optional.empty(), 0f, Optional.empty(), 0f, Optional.empty(), 0f, Optional.empty(), 0f)

			@JvmField
			val CODEC: Codec<Dress> = RecordCodecBuilder.create { i ->
				i.group(
					BlockState.CODEC.optionalFieldOf("hanging").forGetter(Dress::hanging),
					Codec.floatRange(0f, 1f).optionalFieldOf("hanging_chance", 0.15f).forGetter(Dress::hangingChance),
					BlockState.CODEC.optionalFieldOf("bracket").forGetter(Dress::bracket),
					Codec.floatRange(0f, 1f).optionalFieldOf("bracket_chance", 0.06f).forGetter(Dress::bracketChance),
					BlockState.CODEC.optionalFieldOf("hollow").forGetter(Dress::hollow),
					Codec.floatRange(0f, 1f).optionalFieldOf("hollow_chance", 0.4f).forGetter(Dress::hollowChance),
					BlockState.CODEC.optionalFieldOf("carpet").forGetter(Dress::carpet),
					Codec.floatRange(0f, 1f).optionalFieldOf("carpet_chance", 0.5f).forGetter(Dress::carpetChance),
				).apply(i) { a, b, c, d, e, f, g, h -> Dress(a, b, c, d, e, f, g, h) }
			}
		}
	}

	private class Ctx(val level: WorldGenLevel, val r: RandomSource, val cfg: Config, val leaves: BlockState, val cx: Int, val cz: Int) {
		val p = BlockPos.MutableBlockPos()

		/** Inside the 3x3 chunks a feature may write to (the origin's chunk and its neighbours). */
		fun writable(pos: BlockPos): Boolean = Math.abs((pos.x shr 4) - cx) <= 1 && Math.abs((pos.z shr 4) - cz) <= 1
	}

	override fun place(context: FeaturePlaceContext<Config>): Boolean {
		val level = context.level()
		val cfg = context.config()
		val o = context.origin()
		val ground = level.getBlockState(o.below())
		if (!io.github.menonng.biomesmod.compat.LandformCompat.isSoil(ground)) {
			return false
		}
		val leaves = if (cfg.leaves.hasProperty(LeavesBlock.PERSISTENT)) cfg.leaves.setValue(LeavesBlock.PERSISTENT, true) else cfg.leaves
		val c = Ctx(level, context.random(), cfg, leaves, o.x shr 4, o.z shr 4)
		val h = (cfg.height.min + c.r.nextFloat() * (cfg.height.max - cfg.height.min)).toInt()
		val rad = (cfg.radius.min + c.r.nextFloat() * (cfg.radius.max - cfg.radius.min)).toDouble()
		// Room check: the trunk column must be clear for most of its height.
		for (y in 1 until min(h, 12)) {
			val s = level.getBlockState(c.p.set(o.x, o.y + y, o.z))
			if (!s.isAir && !s.canBeReplaced() && !s.`is`(BlockTags.LEAVES)) {
				return false
			}
		}
		if (cfg.style == "spire") spire(c, o, h, rad) else broad(c, o, h, rad)
		dress(c, o, h, rad)
		return true
	}

	// ---- spire (redwood) ----

	private fun spire(c: Ctx, o: BlockPos, h: Int, rad: Double) {
		roots(c, o, rad, 4 + c.r.nextInt(3), rad * 1.8 + 2)
		for (y in 0 until h) {
			val t = y / h.toDouble()
			val rr = rad * (1 - 0.65 * t) + (if (y < 4) (4 - y) * 0.35 else 0.0)
			disc(c, o.x + 0.5, o.y + y, o.z + 0.5, max(0.5, rr), c.cfg.log)
		}
		val crownR = (c.cfg.crown.min + c.r.nextFloat() * (c.cfg.crown.max - c.cfg.crown.min)).toDouble()
		// Branches from 45% up, shorter towards the top, drooping slightly, each with a flat spray of foliage.
		var y = (h * 0.45).toInt()
		while (y < h - 3) {
			val t = (y - h * 0.45) / (h * 0.55)
			val n = 2 + c.r.nextInt(2)
			for (k in 0 until n) {
				val a = c.r.nextFloat() * Math.PI * 2
				val len = crownR * (1 - 0.75 * t) * (0.6 + 0.4 * c.r.nextFloat())
				val trunkR = rad * (1 - 0.65 * (y / h.toDouble()))
				val sx = o.x + 0.5 + cos(a) * trunkR
				val sz = o.z + 0.5 + sin(a) * trunkR
				val ex = sx + cos(a) * len
				val ez = sz + sin(a) * len
				val ey = y - len * 0.15
				limb(c, sx, o.y + y.toDouble(), sz, ex, o.y + ey, ez, 0.5)
				blob(c, ex, o.y + ey + 0.8, ez, 1.6 + len * 0.25, 0.9)
			}
			y += 2 + c.r.nextInt(3)
		}
		// Conical top.
		val top = o.y + h
		for (dy in -6..3) {
			val rr = 2.6 * (1 - (dy + 6) / 10.0) + 0.6
			blob(c, o.x + 0.5, top + dy.toDouble(), o.z + 0.5, rr, 0.6)
		}
	}

	// ---- broad (veteran broadleaf) ----

	private fun broad(c: Ctx, o: BlockPos, h: Int, rad: Double) {
		roots(c, o, rad, 5 + c.r.nextInt(3), rad * 2.2 + 3)
		val split = (h * (0.4 + 0.15 * c.r.nextFloat())).toInt()
		for (y in 0 until split) {
			val rr = rad * (1 - 0.15 * y / split.toDouble()) + (if (y < 3) (3 - y) * 0.4 else 0.0)
			disc(c, o.x + 0.5, o.y + y, o.z + 0.5, rr, c.cfg.log)
		}
		val crownR = (c.cfg.crown.min + c.r.nextFloat() * (c.cfg.crown.max - c.cfg.crown.min)).toDouble()
		val limbs = 3 + c.r.nextInt(3)
		val phase = c.r.nextFloat() * Math.PI * 2
		for (k in 0 until limbs) {
			val a = phase + Math.PI * 2 * k / limbs + (c.r.nextFloat() - 0.5) * 0.6
			val dead = c.r.nextFloat() < c.cfg.deadLimbs
			grow(c, o.x + 0.5, o.y + split - 0.5, o.z + 0.5, a, 0.75 + c.r.nextFloat() * 0.35, (h - split) * 0.42, rad * 0.62, 3, crownR, dead)
		}
	}

	private fun grow(c: Ctx, x: Double, y: Double, z: Double, a: Double, elev: Double, len: Double, thick: Double, depth: Int, crownR: Double,
		dead: Boolean) {
		val dx = cos(a) * cos(elev)
		val dy = sin(elev)
		val dz = sin(a) * cos(elev)
		val ex = x + dx * len
		val ey = y + dy * len
		val ez = z + dz * len
		limb(c, x, y, z, ex, ey, ez, thick)
		if (depth <= 0 || thick < 0.45) {
			if (!dead) {
				blob(c, ex, ey + 1, ez, min(4.5, crownR * 0.3 + c.r.nextFloat() * 1.2), 0.62)
			}
			return
		}
		val n = if (c.r.nextFloat() < 0.35) 3 else 2
		for (k in 0 until n) {
			val na = a + (k - (n - 1) / 2.0) * (0.55 + c.r.nextFloat() * 0.3)
			val ne = (elev + (c.r.nextFloat() - 0.6) * 0.5).coerceIn(0.15, 1.3)
			grow(c, ex, ey, ez, na, ne, len * (0.62 + c.r.nextFloat() * 0.15), thick * 0.68, depth - 1, crownR, dead)
		}
	}

	// ---- dressing ----

	private fun isTrunk(c: Ctx, st: BlockState): Boolean = st.block == c.cfg.log.block || st.block == c.cfg.wood.block

	private fun dress(c: Ctx, o: BlockPos, h: Int, rad: Double) {
		val d = c.cfg.dress
		val p = BlockPos.MutableBlockPos()
		val ri = ceil(rad + 15).toInt()
		val hanging = d.hanging.orElse(null)
		val carpet = d.carpet.orElse(null)
		// One pass over the tree's box: lichen under the leaves, moss on the roots.
		if (hanging != null || carpet != null) {
			for (y in o.y until o.y + h + 8) {
				for (dz in -ri..ri) {
					for (dx in -ri..ri) {
						val st = c.level.getBlockState(p.set(o.x + dx, y, o.z + dz))
						if (hanging != null && st.`is`(BlockTags.LEAVES)) {
							if (c.r.nextFloat() < d.hangingChance && c.level.getBlockState(p.set(o.x + dx, y - 1, o.z + dz)).isAir) {
								if (c.writable(p)) c.level.setBlock(p, hanging, 2)
							}
						} else if (carpet != null && y < o.y + 6 && isTrunk(c, st)) {
							if (c.r.nextFloat() < d.carpetChance && c.level.getBlockState(p.set(o.x + dx, y + 1, o.z + dz)).isAir) {
								if (c.writable(p)) c.level.setBlock(p, carpet, 2)
							}
						}
					}
				}
			}
		}
		val bracket = d.bracket.orElse(null)
		if (bracket != null) {
			for (y in o.y + 1 until o.y + (h * 0.6).toInt()) {
				for (dir in Direction.Plane.HORIZONTAL) {
					if (c.r.nextFloat() >= d.bracketChance) continue
					// Walk out from the axis to the first air block beside the bark.
					for (s in 1..(rad + 4).toInt()) {
						p.set(o.x + dir.stepX * s, y, o.z + dir.stepZ * s)
						if (c.level.getBlockState(p).isAir) {
							val back = c.level.getBlockState(p.set(o.x + dir.stepX * (s - 1), y, o.z + dir.stepZ * (s - 1)))
							if (isTrunk(c, back)) {
								val b = if (bracket.hasProperty(AmethystClusterBlock.FACING)) bracket.setValue(AmethystClusterBlock.FACING, dir) else bracket
								c.level.setBlock(p.set(o.x + dir.stepX * s, y, o.z + dir.stepZ * s), b, 2)
							}
							break
						}
					}
				}
			}
		}
		val hollow = d.hollow.orElse(null)
		if (hollow != null && c.r.nextFloat() < d.hollowChance) {
			val dir = Direction.Plane.HORIZONTAL.getRandomDirection(c.r)
			val y = o.y + 2 + c.r.nextInt(4)
			for (s in (rad + 2).toInt() downTo 0) {
				p.set(o.x + dir.stepX * s, y, o.z + dir.stepZ * s)
				if (isTrunk(c, c.level.getBlockState(p))) {
					c.level.setBlock(p, hollow, 2)
					break
				}
			}
		}
	}

	// ---- shared pieces ----

	/** Buttress roots: wedges of wood running out and down from the trunk foot. */
	private fun roots(c: Ctx, o: BlockPos, rad: Double, n: Int, reach: Double) {
		val phase = c.r.nextFloat() * Math.PI * 2
		for (k in 0 until n) {
			val a = phase + Math.PI * 2 * k / n + (c.r.nextFloat() - 0.5) * 0.5
			val len = reach * (0.6 + 0.4 * c.r.nextFloat())
			var s = rad * 0.6
			while (s < len) {
				val t = s / len
				val x = o.x + 0.5 + cos(a) * s
				val z = o.z + 0.5 + sin(a) * s
				val hgt = ((1 - t) * (2.5 + rad)).toInt()
				for (y in -1..hgt) {
					val by = o.y + y
					val pos = c.p.set(floor(x).toInt(), by, floor(z).toInt())
					val cur = c.level.getBlockState(pos)
					if (y < 0) {
						if (io.github.menonng.biomesmod.compat.LandformCompat.isSoil(cur) && c.writable(pos)) c.level.setBlock(pos, c.cfg.wood, 19)
					} else {
						put(c, pos, c.cfg.wood)
					}
				}
				s += 0.7
			}
		}
	}

	/** A horizontal disc of log (one trunk layer). */
	private fun disc(c: Ctx, cx: Double, y: Int, cz: Double, r: Double, state: BlockState) {
		val ri = ceil(r).toInt()
		for (dz in -ri..ri) {
			for (dx in -ri..ri) {
				val bx = floor(cx).toInt() + dx
				val bz = floor(cz).toInt() + dz
				val ddx = bx + 0.5 - cx
				val ddz = bz + 0.5 - cz
				if (ddx * ddx + ddz * ddz <= r * r + 0.25) {
					put(c, c.p.set(bx, y, bz), state)
				}
			}
		}
	}

	/** A limb from (x0,y0,z0) to (x1,y1,z1), logs oriented along its main axis, thickness in blocks (radius). */
	private fun limb(c: Ctx, x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, thick: Double) {
		val dx = x1 - x0
		val dy = y1 - y0
		val dz = z1 - z0
		val len = sqrt(dx * dx + dy * dy + dz * dz)
		val axis = when {
			abs(dy) >= abs(dx) && abs(dy) >= abs(dz) -> Direction.Axis.Y
			abs(dx) >= abs(dz) -> Direction.Axis.X
			else -> Direction.Axis.Z
		}
		val log = if (c.cfg.log.hasProperty(RotatedPillarBlock.AXIS)) c.cfg.log.setValue(RotatedPillarBlock.AXIS, axis) else c.cfg.log
		val steps = max(1, ceil(len * 1.5).toInt())
		val ri = ceil(thick).toInt()
		for (s in 0..steps) {
			val t = s / steps.toDouble()
			val px = x0 + dx * t
			val py = y0 + dy * t
			val pz = z0 + dz * t
			val r = thick * (1 - 0.3 * t)
			for (oy in -ri..ri) {
				for (oz in -ri..ri) {
					for (ox in -ri..ri) {
						if (ox * ox + oy * oy + oz * oz <= r * r + 0.3) {
							put(c, c.p.set(floor(px).toInt() + ox, floor(py).toInt() + oy, floor(pz).toInt() + oz), log)
						}
					}
				}
			}
		}
	}

	/** A lumpy leaf cloud, flattened by [flat] (vertical radius = r * flat). */
	private fun blob(c: Ctx, cx: Double, cy: Double, cz: Double, r: Double, flat: Double) {
		val ri = ceil(r).toInt()
		val rv = max(1, ceil(r * flat).toInt())
		for (dy in -rv..rv) {
			for (dz in -ri..ri) {
				for (dx in -ri..ri) {
					val q = (dx * dx + dz * dz) / (r * r) + (dy * dy) / ((r * flat) * (r * flat))
					if (q <= 1 - 0.25 * c.r.nextFloat()) {
						put(c, c.p.set(floor(cx).toInt() + dx, floor(cy).toInt() + dy, floor(cz).toInt() + dz), c.leaves)
					}
				}
			}
		}
	}

	private fun put(c: Ctx, pos: BlockPos, state: BlockState) {
		if (!c.writable(pos)) {
			return
		}
		val cur = c.level.getBlockState(pos)
		val isLeaves = state.`is`(BlockTags.LEAVES)
		if (cur.isAir || cur.canBeReplaced() || (!isLeaves && cur.`is`(BlockTags.LEAVES))) {
			c.level.setBlock(pos, state, 19)
		}
	}
}
