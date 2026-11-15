package io.github.menonng.biomesmod.terrain.field

import io.github.menonng.biomesmod.terrain.util.StrataPalette
import net.minecraft.core.BlockPos
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.FallingBlock
import net.minecraft.world.level.block.LiquidBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.material.Fluids
import java.util.Arrays
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Working state for shaping one chunk: a grid covering the chunk plus a ring of `m` columns, edge factors, the
 * original columns, the mode's targets and the resolved ground/water. Reused per thread.
 *
 * Water rules (the reason most of this class exists):
 * - All water a mode adds is a pure function of coordinates, so a neighbouring chunk can predict it.
 * - A dry column next to water that is higher than its ground is raised to hold it ("wall"), so water never spills;
 *   a wet column next to higher water becomes such a wall.
 * - Original water (sea, rivers, lakes) is kept in the edge fade band (and everywhere under the KEEP policy);
 *   reshaped columns next to it are walled the same way. Nothing ever drains.
 * - Every rewritten column is sealed: caves, carver ravines and aquifer pockets under the new surface and in newly
 *   exposed faces are filled, so no water or lava leaks out of walls.
 */
class ChunkFrame private constructor() {
	// context (level and generator are dropped after each chunk so the thread-local frame holds no world)
	private var levelRef: WorldGenLevel? = null
	private var generatorRef: ChunkGenerator? = null
	val level: WorldGenLevel get() = levelRef!!
	val generator: ChunkGenerator get() = generatorRef!!
	lateinit var sampler: FieldSampler
	lateinit var common: Common
	internal lateinit var shaper: Shaper

	@JvmField
	var x0 = 0

	@JvmField
	var z0 = 0

	@JvmField
	var m = 0

	@JvmField
	var n = 0

	@JvmField
	var seaLevel = 0

	@JvmField
	var minY = 0

	@JvmField
	var maxY = 0
	internal lateinit var palette: FieldPalette
	internal var cliffSlope = 0
	internal var writes = 0

	// edge field
	@JvmField
	internal val f = DoubleArray(CAP)

	@JvmField
	internal val d = DoubleArray(CAP)

	// original columns (ring columns are read too: exact when unprocessed, final when processed)
	@JvmField
	internal val o = IntArray(CAP)

	@JvmField
	internal val ow = IntArray(CAP)
	private val top = IntArray(CAP)
	private val origTop = arrayOfNulls<BlockState>(CAP)
	private val origSub = arrayOfNulls<BlockState>(CAP)
	private val origDeep = arrayOfNulls<BlockState>(CAP)

	// mode targets
	private val tGround = DoubleArray(CAP)
	private val tBlend = DoubleArray(CAP)
	private val tMin = DoubleArray(CAP)
	private val tWater = IntArray(CAP)
	private val tKeep = IntArray(CAP)
	private val tFlags = IntArray(CAP)
	private val tFallTop = IntArray(CAP)
	private val tSurfDepth = IntArray(CAP)
	private val tSurface = arrayOfNulls<BlockState>(CAP)

	// resolved
	private val g = IntArray(CAP)
	private val w = IntArray(CAP)
	private val pw = IntArray(CAP)
	private val touched = BooleanArray(CAP)

	// base height grid (16-block nodes, bicubic)
	private var bgx0 = 0
	private var bgz0 = 0
	private var bcnt = 0
	private var blurRadius = -1
	private val rawWin = DoubleArray((SIDE / 16 + 4 + 2 * 8 + 2) * (SIDE / 16 + 4 + 2 * 8 + 2))
	private val raw = DoubleArray(64)
	private val blur = DoubleArray(64)

	// scratch
	private val pos = BlockPos.MutableBlockPos()
	private var outX = DoubleArray(1024)
	private var outZ = DoubleArray(1024)
	private val column = Column()
	private var wetSafe = false

	private var heightsRef: net.minecraft.world.level.LevelHeightAccessor? = null

	// planning (see plan()): the frame is kept between calls for the same chunk and shaper
	private var planShaper: Shaper? = null
	private var planActive = false

	/** Water level (first free block above it) of the column last planned, or NONE. */
	internal var planWater = NONE

	internal fun begin(level: WorldGenLevel, generator: ChunkGenerator, sampler: FieldSampler, common: Common, shaper: Shaper, x0: Int, z0: Int) {
		begin(level, level, generator, sampler, common, shaper, x0, z0)
	}

	private fun begin(level: WorldGenLevel?, heights: net.minecraft.world.level.LevelHeightAccessor, generator: ChunkGenerator, sampler: FieldSampler,
		common: Common, shaper: Shaper, x0: Int, z0: Int) {
		this.levelRef = level
		this.heightsRef = heights
		this.planShaper = null
		this.siteYKnown = false
		this.generatorRef = generator
		this.sampler = sampler
		this.common = common
		this.shaper = shaper
		this.x0 = x0
		this.z0 = z0
		this.m = max(1, min(MAX_MARGIN, shaper.margin()))
		this.n = 16 + 2 * m
		this.seaLevel = sampler.seaLevel
		this.minY = sampler.minY
		this.maxY = sampler.maxY
		this.palette = shaper.defaultPalette().overlay(common.palette.orElse(FieldPalette.EMPTY))
		this.cliffSlope = palette.cliffSlope.orElse(shaper.defaultCliffSlope())
		this.blurRadius = -1
		this.writes = 0
	}

	internal fun end() {
		levelRef = null
		heightsRef = null
		generatorRef = null
		Arrays.fill(tSurface, null)
		Arrays.fill(origTop, null)
		Arrays.fill(origSub, null)
		Arrays.fill(origDeep, null)
	}

	// ------------------------------------------------------------------ grid helpers

	private var siteY = Double.NaN
	private var siteYKnown = false

	/**
	 * The height the site under this chunk built its terrain around (its `target_y`), or NaN outside every site. The
	 * same for every chunk of one site, so a mode can hang an absolute level on it (a valley floor that stays level
	 * while the mountains above it rise and fall).
	 */
	fun siteTargetY(): Double {
		if (!siteYKnown) {
			siteYKnown = true
			siteY = Double.NaN
			val field = io.github.menonng.biomesmod.site.SiteField.forSampler(sampler.randomState.sampler())
			if (field != null) {
				for (k in 0 until 5) {
					val s = field.blend(x0 + (if (k == 0) 8 else if (k % 2 == 1) 0 else 15), z0 + (if (k == 0) 8 else if (k < 3) 0 else 15)).a
					if (s != null) {
						siteY = s.spec.targetY.toDouble()
						break
					}
				}
			}
		}
		return siteY
	}

	fun idx(x: Int, z: Int): Int = (x - x0 + m) + (z - z0 + m) * n

	fun gridX(i: Int): Int = x0 - m + i % n

	fun gridZ(i: Int): Int = z0 - m + i / n

	fun inChunk(i: Int): Boolean {
		val lx = i % n - m
		val lz = i / n - m
		return lx in 0..15 && lz in 0..15
	}

	fun inGrid(x: Int, z: Int): Boolean = x >= x0 - m && x < x0 + 16 + m && z >= z0 - m && z < z0 + 16 + m

	/** Edge factor in [0, 1] (1 = fully inside the biome). */
	fun edge(i: Int): Double = f[i]

	/** Distance (blocks) from the biome edge, FAR when the feature applies everywhere. */
	fun edgeDistance(i: Int): Double = d[i]

	/** Original top ground Y (exact in the chunk; the actual current ground in the ring). */
	fun orig(i: Int): Int = o[i]

	/** Original water level (sea-level convention) above the ground, or NONE. */
	fun origWater(i: Int): Int = ow[i]

	// ------------------------------------------------------------------ edge field

	/** Computes f and d over the grid. Returns false when no column of this chunk is affected. */
	internal fun computeEdge(reach: Int): Boolean {
		val cells = n * n
		if (common.everywhere()) {
			Arrays.fill(f, 0, cells, 1.0)
			Arrays.fill(d, 0, cells, FAR)
			return true
		}
		val blendW = common.edgeBlend
		val amp = common.edgeNoise * blendW
		val window = ceil(blendW + amp).toInt() + reach + 8
		val shift = if (window <= 72) 3 else if (window <= 180) 4 else 5
		val s = 1 shl shift
		val margin = s
		val span = window + margin + s
		val gxa = Math.floorDiv(x0 - m - span, s)
		val gxb = Math.floorDiv(x0 + 15 + m + span, s)
		val gza = Math.floorDiv(z0 - m - span, s)
		val gzb = Math.floorDiv(z0 + 15 + m + span, s)
		var outs = 0
		var ins = 0
		val biomes = common.biomes
		for (gz in gza..gzb) {
			for (gx in gxa..gxb) {
				val b = sampler.biomeNode(gx, gz, shift)
				if (biomes.contains(b)) {
					ins++
				} else {
					if (outs == outX.size) {
						outX = outX.copyOf(outs * 2)
						outZ = outZ.copyOf(outs * 2)
					}
					outX[outs] = (gx shl shift).toDouble()
					outZ[outs] = (gz shl shift).toDouble()
					outs++
				}
			}
		}
		if (ins == 0) {
			return false
		}
		val edgeNoise = EdgeNoise.of(sampler.seed xor common.seed.toLong())
		val noiseScale = max(48.0, blendW * 2.5)
		var any = false
		for (i in 0 until cells) {
			val x = gridX(i)
			val z = gridZ(i)
			var best = Double.MAX_VALUE
			for (k in 0 until outs) {
				val dx = outX[k] - x
				val dz = outZ[k] - z
				val dd = dx * dx + dz * dz
				if (dd < best) {
					best = dd
				}
			}
			var dist = if (outs == 0) window.toDouble() else min(window.toDouble(), sqrt(best) - margin)
			dist = max(0.0, dist)
			d[i] = dist
			val fade = if (blendW <= 0) {
				if (dist > 0) 1.0 else 0.0
			} else {
				val jitter = amp * edgeNoise.fbm01(x.toDouble(), z.toDouble(), noiseScale, 2)
				Noise.smoothstep(0.0, 1.0, (dist - jitter) / blendW)
			}
			f[i] = fade
			if (fade > 0 && inChunk(i)) {
				any = true
			}
		}
		return any
	}

	/** Shared per-seed noise for the edge band jitter. */
	private object EdgeNoise {
		@Volatile
		private var last: Noise? = null

		@Volatile
		private var lastSeed = 0L

		fun of(seed: Long): Noise {
			var n = last
			if (n == null || lastSeed != seed) {
				n = Noise(seed * 0x5DEECE66DL + 0x2545F491L)
				last = n
				lastSeed = seed
			}
			return n
		}
	}

	// ------------------------------------------------------------------ base height

	/** Prepares the noise-only base height field; blurNodes > 0 also prepares a regional (blurred) surface. */
	fun prepareBase(blurNodes: Int) {
		val r = max(0, min(8, blurNodes))
		if (blurRadius == r) {
			return
		}
		blurRadius = r
		bgx0 = Math.floorDiv(x0 - m, 16) - 1
		bgz0 = Math.floorDiv(z0 - m, 16) - 1
		bcnt = Math.floorDiv(x0 + 15 + m, 16) + 2 - bgx0 + 1
		val rc = bcnt + 2 * r
		for (j in 0 until rc) {
			for (i in 0 until rc) {
				rawWin[i + j * rc] = sampler.baseNode(generator, heightsRef!!, bgx0 - r + i, bgz0 - r + j).toDouble()
			}
		}
		for (j in 0 until bcnt) {
			for (i in 0 until bcnt) {
				raw[i + j * bcnt] = rawWin[(i + r) + (j + r) * rc]
			}
		}
		if (r == 0) {
			System.arraycopy(raw, 0, blur, 0, bcnt * bcnt)
			return
		}
		val sigma2 = 0.5 * r * r
		for (j in 0 until bcnt) {
			for (i in 0 until bcnt) {
				var sum = 0.0
				var wsum = 0.0
				for (dj in -r..r) {
					for (di in -r..r) {
						val dd = di * di + dj * dj
						if (dd > r * r) {
							continue
						}
						val wt = exp(-dd / sigma2)
						sum += wt * rawWin[(i + r + di) + (j + r + dj) * rc]
						wsum += wt
					}
				}
				blur[i + j * bcnt] = sum / wsum
			}
		}
	}

	/** Smooth noise-only terrain height (bicubic over the 16-block base grid). Call prepareBase first. */
	fun base(x: Int, z: Int): Double = bicubic(raw, x, z)

	/** Regional (blurred) terrain height. Call prepareBase(radius) first. */
	fun blurBase(x: Int, z: Int): Double = bicubic(blur, x, z)

	private fun bicubic(a: DoubleArray, x: Int, z: Int): Double {
		val fx = (x - bgx0 * 16.0) / 16.0
		val fz = (z - bgz0 * 16.0) / 16.0
		var ci = floor(fx).toInt()
		var cj = floor(fz).toInt()
		var tx = fx - ci
		var tz = fz - cj
		// Defensive: a sample outside the prepared grid is clamped to its edge instead of crashing worldgen.
		if (ci < 1 || ci > bcnt - 3 || cj < 1 || cj > bcnt - 3) {
			ci = max(1, min(bcnt - 3, ci))
			cj = max(1, min(bcnt - 3, cj))
			tx = Noise.clamp(tx, 0.0, 1.0)
			tz = Noise.clamp(tz, 0.0, 1.0)
		}
		val r0 = row(a, ci, cj - 1, tx)
		val r1 = row(a, ci, cj, tx)
		val r2 = row(a, ci, cj + 1, tx)
		val r3 = row(a, ci, cj + 2, tx)
		return catmull(r0, r1, r2, r3, tz)
	}

	private fun row(a: DoubleArray, ci: Int, cj: Int, t: Double): Double {
		val base = cj * bcnt
		return catmull(a[base + ci - 1], a[base + ci], a[base + ci + 1], a[base + ci + 2], t)
	}

	// ------------------------------------------------------------------ original columns

	internal fun readColumns() {
		for (i in 0 until n * n) {
			readColumn(i, gridX(i), gridZ(i))
		}
	}

	private fun readColumn(i: Int, x: Int, z: Int) {
		val ws = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1
		top[i] = ws
		var y = ws
		var waterTop = NONE
		var s = Blocks.AIR.defaultBlockState()
		while (y > minY) {
			pos.set(x, y, z)
			s = level.getBlockState(pos)
			if (isGround(s)) {
				break
			}
			if (waterTop == NONE) {
				val fs = s.fluidState
				if ((!fs.isEmpty && fs.isSource) || s.`is`(Blocks.ICE)) {
					waterTop = y
				}
			}
			y--
		}
		o[i] = y
		ow[i] = if (waterTop == NONE) NONE else waterTop + 1
		if (inChunk(i)) {
			val t = unsnow(s)
			origTop[i] = t
			pos.set(x, y - 1, z)
			val sub = level.getBlockState(pos)
			origSub[i] = if (isGround(sub)) unsnow(sub) else t
			pos.set(x, y - 4, z)
			val deep = level.getBlockState(pos)
			origDeep[i] = if (isGround(deep) && deep.block !is FallingBlock) deep
			else if (y - 4 < 0) Blocks.DEEPSLATE.defaultBlockState() else Blocks.STONE.defaultBlockState()
		}
	}

	// ------------------------------------------------------------------ planning

	/**
	 * The ground this shaper will give column (x, z), without a generated chunk: for structure planning, which happens
	 * before any block exists. [ground] is the top ground block there before the shaper. The other columns of the frame
	 * stand on the smooth noise terrain, and the water walls are left out, so this is the mode's own shape of the land.
	 */
	private fun planColumn(heights: net.minecraft.world.level.LevelHeightAccessor, generator: ChunkGenerator, sampler: FieldSampler, common: Common,
		shaper: Shaper, x: Int, z: Int, ground: Int): Int {
		val cx = x and 15.inv()
		val cz = z and 15.inv()
		if (planShaper !== shaper || x0 != cx || z0 != cz || this.sampler !== sampler) {
			begin(null, heights, generator, sampler, common, shaper, cx, cz)
			planShaper = shaper
			planActive = computeEdge(shaper.edgeReach())
			if (planActive) {
				prepareBase(0)
				for (i in 0 until n * n) {
					o[i] = floor(base(gridX(i), gridZ(i)) + 0.5).toInt()
					ow[i] = if (o[i] < seaLevel - 1) seaLevel else NONE
				}
				shaper.prepare(this)
			}
		}
		planWater = NONE
		if (!planActive) {
			return ground
		}
		val i = idx(x, z)
		if (f[i] <= 0) {
			return ground
		}
		o[i] = ground
		ow[i] = if (ground < seaLevel - 1) seaLevel else NONE
		val c = column
		c.reset(ground.toDouble())
		shaper.column(this, x, z, i, c)
		val b = if (c.blend >= 0) c.blend else f[i]
		var gg = if (b > 0) ground + (c.ground - ground) * b else ground.toDouble()
		if (b > 0 && c.minGround > gg) {
			gg = c.minGround
		}
		val gi = max(minY + 1, min(maxY - 2, floor(gg + 0.5).toInt()))
		var wi = NONE
		if (b > 0 && c.water != NONE && f[i] >= WET_F && gi < c.water - 1) {
			wi = c.water
		}
		val keepOrig = b <= 0 || shaper.waterPolicy() == Shaper.WaterPolicy.KEEP || f[i] < WET_F
		if (ow[i] != NONE && keepOrig && c.keepWaterBelow == NONE && gi < ow[i] - 1) {
			wi = max(wi, ow[i])
		}
		planWater = wi
		return gi
	}

	// ------------------------------------------------------------------ targets, hydrology, walls

	internal fun computeTargets() {
		val c = column
		for (i in 0 until n * n) {
			c.reset(o[i].toDouble())
			shaper.column(this, gridX(i), gridZ(i), i, c)
			tGround[i] = c.ground
			tWater[i] = c.water
			tSurface[i] = c.surface
			tSurfDepth[i] = c.surfaceDepth
			tKeep[i] = c.keepWaterBelow
			tBlend[i] = c.blend
			tMin[i] = c.minGround
			tFlags[i] = c.flags
			tFallTop[i] = c.fallTop
		}
	}

	private fun blendOf(i: Int): Double = if (tBlend[i] >= 0) tBlend[i] else f[i]

	internal fun resolve() {
		val cells = n * n
		val keepPolicy = shaper.waterPolicy() == Shaper.WaterPolicy.KEEP
		for (i in 0 until cells) {
			val b = blendOf(i)
			val active = b > 0 && f[i] > 0
			var gg = if (active) o[i] + (tGround[i] - o[i]) * b else o[i].toDouble()
			if (active && tMin[i] > gg) {
				gg = tMin[i]
			}
			var gi = floor(gg + 0.5).toInt()
			gi = max(minY + 1, min(maxY - 2, gi))
			var wi = NONE
			if (active && tWater[i] != NONE && f[i] >= WET_F && gi < tWater[i] - 1) {
				wi = tWater[i]
			}
			val keepOrig = !active || keepPolicy || f[i] < WET_F
			if (ow[i] != NONE && keepOrig && tKeep[i] == NONE && gi < ow[i] - 1) {
				wi = max(wi, ow[i])
			}
			g[i] = gi
			w[i] = wi
			touched[i] = active
			if (inChunk(i)) {
				pw[i] = wi
			} else {
				// Prediction for a neighbouring chunk's column: its pure mode water (assumed wet) and any original water it
				// will keep. Conservative: a dry neighbour only causes an unnecessary low wall.
				var p = NONE
				if (active && tWater[i] != NONE && f[i] >= WET_F) {
					p = tWater[i]
				}
				if (ow[i] != NONE && keepOrig) {
					p = max(p, ow[i])
				}
				pw[i] = p
			}
		}
		// walls: hold back any neighbouring water higher than this column's ground
		for (lz in 0 until 16) {
			for (lx in 0 until 16) {
				val i = (lx + m) + (lz + m) * n
				if (f[i] <= 0 || (tFlags[i] and Column.NO_WALL) != 0) {
					continue
				}
				var need = NONE
				need = wallNeed(i, i - 1, need)
				need = wallNeed(i, i + 1, need)
				need = wallNeed(i, i - n, need)
				need = wallNeed(i, i + n, need)
				if (need != NONE && g[i] < need - 1) {
					g[i] = need - 1
					touched[i] = true
					if (w[i] != NONE && g[i] >= w[i] - 1) {
						w[i] = NONE
					}
				}
			}
		}
	}

	private fun wallNeed(i: Int, nb: Int, need: Int): Int {
		val p = pw[nb]
		if (p == NONE || (w[i] != NONE && p <= w[i])) {
			return need
		}
		return max(need, p)
	}

	// ------------------------------------------------------------------ writing

	internal fun write() {
		val dressNoise = shaper.noise.fork(0x0D7E55)
		for (lz in 0 until 16) {
			for (lx in 0 until 16) {
				val i = (lx + m) + (lz + m) * n
				if (touched[i]) {
					writeColumn(i, x0 + lx, z0 + lz, dressNoise)
				}
			}
		}
	}

	private fun writeColumn(i: Int, x: Int, z: Int, dressNoise: Noise) {
		val gi = g[i]
		val wi = w[i]
		val oi = o[i]
		val full = shaper.fullSurface()
		val dressT = 0.22 + 0.22 * dressNoise.fbm01(x.toDouble(), z.toDouble(), 40.0, 2)
		val usePalette = !full || f[i] >= dressT || (tFlags[i] and Column.FORCE_DRESS) != 0
		val waterChanged = wi != ow[i] || tKeep[i] != NONE
		val fall = (tFlags[i] and Column.FALL) != 0 && tFallTop[i] != NONE
		if (gi == oi && !waterChanged && tSurface[i] == null && !fall && !(full && usePalette)) {
			return
		}
		val waterState = palette.fluid.orElse(Blocks.WATER.defaultBlockState())
		val air = Blocks.AIR.defaultBlockState()

		// 1) above ground: air, water, waterfall (a source block on top keeps the fall running)
		var hi = max(top[i], max(gi, if (wi == NONE) gi else wi - 1))
		if (fall) {
			hi = max(hi, tFallTop[i])
		}
		val fallBottom = if (wi != NONE) wi else gi + 1
		for (y in hi downTo gi + 1) {
			val want = if (fall && y <= tFallTop[i] && y >= fallBottom) {
				Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, if (y == tFallTop[i]) 0 else 8)
			} else if (wi != NONE && y < wi) {
				waterState
			} else {
				air
			}
			set(x, y, z, want)
		}
		if (fall) {
			pos.set(x, tFallTop[i], z)
			level.scheduleTick(pos.immutable(), Fluids.WATER, 5)
		}

		// 2) ground: dress, fill, cliff faces, seal
		val wet = wi != NONE && wi - 1 > gi
		var layers: List<FieldPalette.Layer>? = if (usePalette) (if (wet) palette.underwater.orElse(null) else palette.top.orElse(null)) else null
		if (layers == null && usePalette && wet && palette.top.isPresent) {
			layers = palette.top.get()
			wetSafe = true
		} else {
			wetSafe = false
		}
		var autoTop = origTop[i]!!
		var autoSub = origSub[i]!!
		if (wet && (layers == null || wetSafe)) {
			autoTop = underwaterSafe(autoTop)
			autoSub = underwaterSafe(autoSub)
		}
		val topDepth = if (layers == null) 4 else layers.sumOf { it.depth }
		val minNb = min(min(g[i - 1], g[i + 1]), min(g[i - n], g[i + n]))
		val cliff = gi - minNb >= cliffSlope
		val strata = if (usePalette) palette.strata.orElse(null) else null
		val seal = common.sealDepth
		val sealBottom = max(minY + 1, max(gi - 64, min(gi - seal, minNb + 1)))
		var bottom = min(sealBottom, gi - topDepth + 1)
		if (gi > oi) {
			bottom = min(bottom, oi - 1)
		}
		val keep = tKeep[i]
		val surface = tSurface[i]
		val surfDepth = tSurfDepth[i]
		var lowestSet = Int.MAX_VALUE
		for (y in gi downTo bottom) {
			val depth = gi - y
			pos.set(x, y, z)
			val cur = level.getBlockState(pos)
			if (keep != NONE && y < keep && y > oi) {
				if (cur !== waterState) {
					setRaw(waterState)
				}
				continue
			}
			var want: BlockState? = null
			if (surface != null && depth < surfDepth && !cliff) {
				want = surface
			} else if (!cliff && depth < topDepth) {
				want = layerAt(layers, depth, autoTop, autoSub)
				if (depth == 0 && usePalette && palette.patches.isNotEmpty()) {
					want = patch(x, z, wet, want)
				}
			} else if (cliff && strata != null && y > minNb) {
				want = strata.strataAt(x, y, z)
			} else if (y > oi || (gi > oi && y >= oi - 1)) {
				want = fill(i, x, y, z, strata)
			} else if (y >= sealBottom && (cur.isAir || !cur.fluidState.isEmpty)) {
				want = fill(i, x, y, z, strata)
			}
			if (want != null && want !== cur) {
				setRaw(want)
				lowestSet = y
			}
		}
		// 3) never leave falling blocks over a void
		if (lowestSet != Int.MAX_VALUE) {
			pos.set(x, lowestSet, z)
			val low = level.getBlockState(pos)
			if (low.block is FallingBlock) {
				pos.set(x, lowestSet - 1, z)
				val below = level.getBlockState(pos)
				if (below.isAir || !below.fluidState.isEmpty) {
					pos.set(x, lowestSet, z)
					setRaw(support(low))
				}
			}
		}
	}

	private fun layerAt(layers: List<FieldPalette.Layer>?, depth: Int, autoTop: BlockState, autoSub: BlockState): BlockState {
		if (layers == null) {
			return if (depth == 0) autoTop else autoSub
		}
		var k = depth
		for (l in layers) {
			if (k < l.depth) {
				return if (wetSafe) underwaterSafe(l.block) else l.block
			}
			k -= l.depth
		}
		return layers.last().block
	}

	private fun patch(x: Int, z: Int, wet: Boolean, base: BlockState): BlockState {
		val patches = palette.patches
		for (k in patches.indices) {
			val p = patches[k]
			if (wet != p.underwater) {
				continue
			}
			val v = shaper.noise.fork(0x9A7C + k).fbm(x.toDouble(), z.toDouble(), p.scale, 3)
			if (v > Coverage.threshold(p.coverage, 3)) {
				return p.block
			}
		}
		return base
	}

	private fun fill(i: Int, x: Int, y: Int, z: Int, strata: StrataPalette?): BlockState = strata?.strataAt(x, y, z) ?: origDeep[i]!!

	private fun set(x: Int, y: Int, z: Int, want: BlockState) {
		pos.set(x, y, z)
		if (level.getBlockState(pos) !== want) {
			setRaw(want)
		}
	}

	private fun setRaw(want: BlockState) {
		level.setBlock(pos, want, WRITE_FLAGS)
		writes++
	}

	companion object {
		const val NONE = Column.NONE

		/** Edge factor from which mode water may exist and original water is replaced (REPLACE policy). */
		const val WET_F = 0.5
		const val FAR = 1.0e6
		internal const val MAX_MARGIN = 8
		private const val SIDE = 16 + 2 * MAX_MARGIN
		private const val CAP = SIDE * SIDE
		private const val WRITE_FLAGS = 2 or 16
		private val LOCAL: ThreadLocal<ChunkFrame> = ThreadLocal.withInitial { ChunkFrame() }

		internal fun get(): ChunkFrame = LOCAL.get()

		private val PLAN: ThreadLocal<ChunkFrame> = ThreadLocal.withInitial { ChunkFrame() }

		/** See [planColumn]; the frame's [planWater] holds the column's water afterwards. Uses a frame of its own. */
		internal fun plan(heights: net.minecraft.world.level.LevelHeightAccessor, generator: ChunkGenerator, sampler: FieldSampler, common: Common, shaper: Shaper,
			x: Int, z: Int, ground: Int): Int = PLAN.get().planColumn(heights, generator, sampler, common, shaper, x, z, ground)

		internal fun planWater(): Int = PLAN.get().planWater

		internal fun isGround(s: BlockState): Boolean {
			if (s.isAir || !s.fluidState.isEmpty || s.canBeReplaced()) {
				return false
			}
			if (s.`is`(BlockTags.LEAVES) || s.`is`(BlockTags.LOGS) || s.`is`(Blocks.ICE) || s.`is`(Blocks.FROSTED_ICE)) {
				return false
			}
			if (s.`is`(Blocks.CACTUS) || s.`is`(Blocks.BAMBOO) || s.`is`(Blocks.MUSHROOM_STEM) || s.`is`(Blocks.BROWN_MUSHROOM_BLOCK) ||
				s.`is`(Blocks.RED_MUSHROOM_BLOCK) || s.`is`(Blocks.PUMPKIN) || s.`is`(Blocks.MELON) || s.`is`(Blocks.BEE_NEST) ||
				s.`is`(Blocks.SUGAR_CANE) || s.`is`(Blocks.COCOA)) {
				return false
			}
			return s.isSolid
		}

		private fun unsnow(s: BlockState): BlockState =
			if (s.hasProperty(BlockStateProperties.SNOWY)) s.setValue(BlockStateProperties.SNOWY, false) else s

		private fun catmull(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double =
			p1 + 0.5 * t * (p2 - p0 + t * (2 * p0 - 5 * p1 + 4 * p2 - p3 + t * (3 * (p1 - p2) + p3 - p0)))

		private fun underwaterSafe(s: BlockState): BlockState {
			if (s.`is`(Blocks.GRASS_BLOCK) || s.`is`(Blocks.PODZOL) || s.`is`(Blocks.MYCELIUM) || s.`is`(Blocks.DIRT_PATH)) {
				return Blocks.DIRT.defaultBlockState()
			}
			if (s.`is`(Blocks.SNOW_BLOCK) || s.`is`(Blocks.POWDER_SNOW)) {
				return Blocks.GRAVEL.defaultBlockState()
			}
			return s
		}

		private fun support(falling: BlockState): BlockState {
			if (falling.`is`(Blocks.RED_SAND)) {
				return Blocks.RED_SANDSTONE.defaultBlockState()
			}
			if (falling.`is`(Blocks.GRAVEL) || falling.`is`(Blocks.SUSPICIOUS_GRAVEL)) {
				return Blocks.STONE.defaultBlockState()
			}
			return Blocks.SANDSTONE.defaultBlockState() // sand, concrete powder
		}
	}
}
