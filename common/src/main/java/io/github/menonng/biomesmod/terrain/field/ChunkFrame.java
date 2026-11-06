package io.github.menonng.biomesmod.terrain.field;

import io.github.menonng.biomesmod.terrain.util.StrataPalette;
import java.util.Arrays;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

/**
 * Working state for shaping one chunk: a grid covering the chunk plus a ring of {@code m} columns, edge factors,
 * the original columns, the mode's targets and the resolved ground/water. Reused per thread.
 *
 * <p>Water rules (the reason most of this class exists):
 * <ul>
 * <li>All water a mode adds is a pure function of coordinates, so a neighbouring chunk can predict it.</li>
 * <li>A dry column next to water that is higher than its ground is raised to hold it ("wall"), so water never
 * spills; a wet column next to higher water becomes such a wall.</li>
 * <li>Original water (sea, rivers, lakes) is kept in the edge fade band (and everywhere under the KEEP policy);
 * reshaped columns next to it are walled the same way. Nothing ever drains.</li>
 * <li>Every rewritten column is sealed: caves, carver ravines and aquifer pockets under the new surface and in
 * newly exposed faces are filled, so no water or lava leaks out of walls.</li>
 * </ul>
 */
public final class ChunkFrame {
	public static final int NONE = Column.NONE;
	/** Edge factor from which mode water may exist and original water is replaced (REPLACE policy). */
	public static final double WET_F = 0.5;
	public static final double FAR = 1.0e6;
	static final int MAX_MARGIN = 8;
	private static final int SIDE = 16 + 2 * MAX_MARGIN;
	private static final int CAP = SIDE * SIDE;
	private static final int WRITE_FLAGS = 2 | 16;
	private static final ThreadLocal<ChunkFrame> LOCAL = ThreadLocal.withInitial(ChunkFrame::new);

	// context
	public WorldGenLevel level;
	public ChunkGenerator generator;
	public FieldSampler sampler;
	public Common common;
	Shaper shaper;
	public int x0;
	public int z0;
	public int m;
	public int n;
	public int seaLevel;
	public int minY;
	public int maxY;
	FieldPalette palette;
	int cliffSlope;
	int writes;

	// edge field
	final double[] f = new double[CAP];
	final double[] d = new double[CAP];
	// original columns (ring columns are read too: exact when unprocessed, final when processed)
	final int[] o = new int[CAP];
	final int[] ow = new int[CAP];
	final int[] top = new int[CAP];
	final BlockState[] origTop = new BlockState[CAP];
	final BlockState[] origSub = new BlockState[CAP];
	final BlockState[] origDeep = new BlockState[CAP];
	// mode targets
	final double[] tGround = new double[CAP];
	final double[] tBlend = new double[CAP];
	final double[] tMin = new double[CAP];
	final int[] tWater = new int[CAP];
	final int[] tKeep = new int[CAP];
	final int[] tFlags = new int[CAP];
	final int[] tFallTop = new int[CAP];
	final int[] tSurfDepth = new int[CAP];
	final BlockState[] tSurface = new BlockState[CAP];
	// resolved
	final int[] g = new int[CAP];
	final int[] w = new int[CAP];
	final int[] pw = new int[CAP];
	final boolean[] touched = new boolean[CAP];
	final boolean[] skip = new boolean[CAP];

	// base height grid (16-block nodes, bicubic)
	private int bgx0;
	private int bgz0;
	private int bcnt;
	private int blurRadius = -1;
	private final double[] rawWin = new double[(SIDE / 16 + 4 + 2 * 8 + 2) * (SIDE / 16 + 4 + 2 * 8 + 2)];
	private final double[] raw = new double[64];
	private final double[] blur = new double[64];

	// scratch
	final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	private double[] outX = new double[1024];
	private double[] outZ = new double[1024];
	private final Column column = new Column();

	static ChunkFrame get() {
		return LOCAL.get();
	}

	void begin(WorldGenLevel level, ChunkGenerator generator, FieldSampler sampler, Common common, Shaper shaper, int x0, int z0) {
		this.level = level;
		this.generator = generator;
		this.sampler = sampler;
		this.common = common;
		this.shaper = shaper;
		this.x0 = x0;
		this.z0 = z0;
		this.m = Math.max(1, Math.min(MAX_MARGIN, shaper.margin()));
		this.n = 16 + 2 * m;
		this.seaLevel = sampler.seaLevel();
		this.minY = sampler.minY();
		this.maxY = sampler.maxY();
		this.palette = shaper.defaultPalette().overlay(common.palette().orElse(FieldPalette.EMPTY));
		this.cliffSlope = palette.cliffSlope().orElse(shaper.defaultCliffSlope());
		this.blurRadius = -1;
		this.writes = 0;
	}

	void end() {
		level = null;
		generator = null;
		Arrays.fill(tSurface, null);
		Arrays.fill(origTop, null);
		Arrays.fill(origSub, null);
		Arrays.fill(origDeep, null);
	}

	// ------------------------------------------------------------------ grid helpers

	public int idx(int x, int z) {
		return (x - x0 + m) + (z - z0 + m) * n;
	}

	public int gridX(int i) {
		return x0 - m + i % n;
	}

	public int gridZ(int i) {
		return z0 - m + i / n;
	}

	public boolean inChunk(int i) {
		int lx = i % n - m;
		int lz = i / n - m;
		return lx >= 0 && lx < 16 && lz >= 0 && lz < 16;
	}

	public boolean inGrid(int x, int z) {
		return x >= x0 - m && x < x0 + 16 + m && z >= z0 - m && z < z0 + 16 + m;
	}

	/** Edge factor in [0, 1] (1 = fully inside the biome). */
	public double edge(int i) {
		return f[i];
	}

	/** Distance (blocks) from the biome edge, FAR when the feature applies everywhere. */
	public double edgeDistance(int i) {
		return d[i];
	}

	/** Original top ground Y (exact in the chunk; the actual current ground in the ring). */
	public int orig(int i) {
		return o[i];
	}

	/** Original water level (sea-level convention) above the ground, or NONE. */
	public int origWater(int i) {
		return ow[i];
	}

	// ------------------------------------------------------------------ edge field

	/** Computes f and d over the grid. Returns false when no column of this chunk is affected. */
	boolean computeEdge(int reach) {
		int cells = n * n;
		if (common.everywhere()) {
			Arrays.fill(f, 0, cells, 1.0);
			Arrays.fill(d, 0, cells, FAR);
			return true;
		}
		int blendW = common.edgeBlend();
		double amp = common.edgeNoise() * blendW;
		int window = (int) Math.ceil(blendW + amp) + reach + 8;
		int shift = window <= 72 ? 3 : window <= 180 ? 4 : 5;
		int s = 1 << shift;
		int margin = s;
		int span = window + margin + s;
		int gxa = Math.floorDiv(x0 - m - span, s);
		int gxb = Math.floorDiv(x0 + 15 + m + span, s);
		int gza = Math.floorDiv(z0 - m - span, s);
		int gzb = Math.floorDiv(z0 + 15 + m + span, s);
		int outs = 0;
		int ins = 0;
		var biomes = common.biomes();
		for (int gz = gza; gz <= gzb; gz++) {
			for (int gx = gxa; gx <= gxb; gx++) {
				Holder<Biome> b = sampler.biomeNode(gx, gz, shift);
				if (biomes.contains(b)) {
					ins++;
				} else {
					if (outs == outX.length) {
						outX = Arrays.copyOf(outX, outs * 2);
						outZ = Arrays.copyOf(outZ, outs * 2);
					}
					outX[outs] = gx << shift;
					outZ[outs] = gz << shift;
					outs++;
				}
			}
		}
		if (ins == 0) {
			return false;
		}
		Noise edgeNoise = EdgeNoise.of(sampler.seed() ^ common.seed());
		double noiseScale = Math.max(48, blendW * 2.5);
		boolean any = false;
		for (int i = 0; i < cells; i++) {
			int x = gridX(i);
			int z = gridZ(i);
			double best = Double.MAX_VALUE;
			for (int k = 0; k < outs; k++) {
				double dx = outX[k] - x;
				double dz = outZ[k] - z;
				double dd = dx * dx + dz * dz;
				if (dd < best) {
					best = dd;
				}
			}
			double dist = outs == 0 ? window : Math.min(window, Math.sqrt(best) - margin);
			dist = Math.max(0, dist);
			d[i] = dist;
			double fade;
			if (blendW <= 0) {
				fade = dist > 0 ? 1 : 0;
			} else {
				double jitter = amp * edgeNoise.fbm01(x, z, noiseScale, 2);
				fade = Noise.smoothstep(0, 1, (dist - jitter) / blendW);
			}
			f[i] = fade;
			if (fade > 0 && inChunk(i)) {
				any = true;
			}
		}
		return any;
	}

	/** Shared per-seed noise for the edge band jitter. */
	private static final class EdgeNoise {
		private static volatile Noise last;
		private static volatile long lastSeed;

		static Noise of(long seed) {
			Noise n = last;
			if (n == null || lastSeed != seed) {
				n = new Noise(seed * 0x5DEECE66DL + 0x2545F491L);
				last = n;
				lastSeed = seed;
			}
			return n;
		}
	}

	// ------------------------------------------------------------------ base height

	/** Prepares the noise-only base height field; blurNodes > 0 also prepares a regional (blurred) surface. */
	public void prepareBase(int blurNodes) {
		int r = Math.max(0, Math.min(8, blurNodes));
		if (blurRadius == r) {
			return;
		}
		blurRadius = r;
		bgx0 = Math.floorDiv(x0 - m, 16) - 1;
		bgz0 = Math.floorDiv(z0 - m, 16) - 1;
		bcnt = Math.floorDiv(x0 + 15 + m, 16) + 2 - bgx0 + 1;
		int rc = bcnt + 2 * r;
		for (int j = 0; j < rc; j++) {
			for (int i = 0; i < rc; i++) {
				rawWin[i + j * rc] = sampler.baseNode(generator, level, bgx0 - r + i, bgz0 - r + j);
			}
		}
		for (int j = 0; j < bcnt; j++) {
			for (int i = 0; i < bcnt; i++) {
				raw[i + j * bcnt] = rawWin[(i + r) + (j + r) * rc];
			}
		}
		if (r == 0) {
			System.arraycopy(raw, 0, blur, 0, bcnt * bcnt);
			return;
		}
		double sigma2 = 0.5 * r * r;
		for (int j = 0; j < bcnt; j++) {
			for (int i = 0; i < bcnt; i++) {
				double sum = 0;
				double wsum = 0;
				for (int dj = -r; dj <= r; dj++) {
					for (int di = -r; di <= r; di++) {
						int dd = di * di + dj * dj;
						if (dd > r * r) {
							continue;
						}
						double wt = Math.exp(-dd / sigma2);
						sum += wt * rawWin[(i + r + di) + (j + r + dj) * rc];
						wsum += wt;
					}
				}
				blur[i + j * bcnt] = sum / wsum;
			}
		}
	}

	/** Smooth noise-only terrain height (bicubic over the 16-block base grid). Call prepareBase first. */
	public double base(int x, int z) {
		return bicubic(raw, x, z);
	}

	/** Regional (blurred) terrain height. Call prepareBase(radius) first. */
	public double blurBase(int x, int z) {
		return bicubic(blur, x, z);
	}

	private double bicubic(double[] a, int x, int z) {
		double fx = (x - bgx0 * 16.0) / 16.0;
		double fz = (z - bgz0 * 16.0) / 16.0;
		int ci = (int) Math.floor(fx);
		int cj = (int) Math.floor(fz);
		double tx = fx - ci;
		double tz = fz - cj;
		double r0 = row(a, ci, cj - 1, tx);
		double r1 = row(a, ci, cj, tx);
		double r2 = row(a, ci, cj + 1, tx);
		double r3 = row(a, ci, cj + 2, tx);
		return catmull(r0, r1, r2, r3, tz);
	}

	private double row(double[] a, int ci, int cj, double t) {
		int base = cj * bcnt;
		return catmull(a[base + ci - 1], a[base + ci], a[base + ci + 1], a[base + ci + 2], t);
	}

	private static double catmull(double p0, double p1, double p2, double p3, double t) {
		return p1 + 0.5 * t * (p2 - p0 + t * (2 * p0 - 5 * p1 + 4 * p2 - p3 + t * (3 * (p1 - p2) + p3 - p0)));
	}

	// ------------------------------------------------------------------ original columns

	static boolean isGround(BlockState s) {
		if (s.isAir() || !s.getFluidState().isEmpty() || s.canBeReplaced()) {
			return false;
		}
		if (s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.is(Blocks.ICE) || s.is(Blocks.FROSTED_ICE)) {
			return false;
		}
		if (s.is(Blocks.CACTUS) || s.is(Blocks.BAMBOO) || s.is(Blocks.MUSHROOM_STEM) || s.is(Blocks.BROWN_MUSHROOM_BLOCK)
				|| s.is(Blocks.RED_MUSHROOM_BLOCK) || s.is(Blocks.PUMPKIN) || s.is(Blocks.MELON) || s.is(Blocks.BEE_NEST)
				|| s.is(Blocks.SUGAR_CANE) || s.is(Blocks.COCOA)) {
			return false;
		}
		return s.isSolid();
	}

	void readColumns() {
		int cells = n * n;
		for (int i = 0; i < cells; i++) {
			readColumn(i, gridX(i), gridZ(i));
		}
	}

	private void readColumn(int i, int x, int z) {
		int ws = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1;
		top[i] = ws;
		int y = ws;
		int waterTop = NONE;
		BlockState s = Blocks.AIR.defaultBlockState();
		while (y > minY) {
			pos.set(x, y, z);
			s = level.getBlockState(pos);
			if (isGround(s)) {
				break;
			}
			if (waterTop == NONE) {
				FluidState fs = s.getFluidState();
				if ((!fs.isEmpty() && fs.isSource()) || s.is(Blocks.ICE)) {
					waterTop = y;
				}
			}
			y--;
		}
		o[i] = y;
		ow[i] = waterTop == NONE ? NONE : waterTop + 1;
		if (inChunk(i)) {
			origTop[i] = unsnow(s);
			pos.set(x, y - 1, z);
			BlockState sub = level.getBlockState(pos);
			origSub[i] = isGround(sub) ? unsnow(sub) : origTop[i];
			pos.set(x, y - 4, z);
			BlockState deep = level.getBlockState(pos);
			origDeep[i] = isGround(deep) && !(deep.getBlock() instanceof FallingBlock) ? deep : y - 4 < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
		}
	}

	private static BlockState unsnow(BlockState s) {
		return s.hasProperty(BlockStateProperties.SNOWY) ? s.setValue(BlockStateProperties.SNOWY, false) : s;
	}

	// ------------------------------------------------------------------ targets, hydrology, walls

	void computeTargets() {
		int cells = n * n;
		Column c = column;
		for (int i = 0; i < cells; i++) {
			c.reset(o[i]);
			shaper.column(this, gridX(i), gridZ(i), i, c);
			tGround[i] = c.ground;
			tWater[i] = c.water;
			tSurface[i] = c.surface;
			tSurfDepth[i] = c.surfaceDepth;
			tKeep[i] = c.keepWaterBelow;
			tBlend[i] = c.blend;
			tMin[i] = c.minGround;
			tFlags[i] = c.flags;
			tFallTop[i] = c.fallTop;
		}
	}

	private double blendOf(int i) {
		return tBlend[i] >= 0 ? tBlend[i] : f[i];
	}

	void resolve() {
		int cells = n * n;
		boolean keepPolicy = shaper.waterPolicy() == Shaper.WaterPolicy.KEEP;
		for (int i = 0; i < cells; i++) {
			double b = blendOf(i);
			boolean active = b > 0 && f[i] > 0;
			double gg = active ? o[i] + (tGround[i] - o[i]) * b : o[i];
			if (active && tMin[i] > gg) {
				gg = tMin[i];
			}
			int gi = (int) Math.floor(gg + 0.5);
			gi = Math.max(minY + 1, Math.min(maxY - 2, gi));
			int wi = NONE;
			if (active && tWater[i] != NONE && f[i] >= WET_F && gi < tWater[i] - 1) {
				wi = tWater[i];
			}
			boolean keepOrig = !active || keepPolicy || f[i] < WET_F;
			if (ow[i] != NONE && keepOrig && tKeep[i] == NONE && gi < ow[i] - 1) {
				wi = Math.max(wi, ow[i]);
			}
			g[i] = gi;
			w[i] = wi;
			touched[i] = active;
			if (inChunk(i)) {
				pw[i] = wi;
			} else {
				// Prediction for a neighbouring chunk's column: its pure mode water (assumed wet) and any original
				// water it will keep. Conservative: a dry neighbour only causes an unnecessary low wall.
				int p = NONE;
				if (active && tWater[i] != NONE && f[i] >= WET_F) {
					p = tWater[i];
				}
				if (ow[i] != NONE && keepOrig) {
					p = Math.max(p, ow[i]);
				}
				pw[i] = p;
			}
		}
		// walls: hold back any neighbouring water higher than this column's ground
		for (int lz = 0; lz < 16; lz++) {
			for (int lx = 0; lx < 16; lx++) {
				int i = (lx + m) + (lz + m) * n;
				if (f[i] <= 0 || (tFlags[i] & Column.NO_WALL) != 0) {
					continue;
				}
				int need = NONE;
				need = wallNeed(i, i - 1, need);
				need = wallNeed(i, i + 1, need);
				need = wallNeed(i, i - n, need);
				need = wallNeed(i, i + n, need);
				if (need != NONE && g[i] < need - 1) {
					g[i] = need - 1;
					touched[i] = true;
					if (w[i] != NONE && g[i] >= w[i] - 1) {
						w[i] = NONE;
					}
				}
			}
		}
	}

	private int wallNeed(int i, int nb, int need) {
		int p = pw[nb];
		if (p == NONE || (w[i] != NONE && p <= w[i])) {
			return need;
		}
		return Math.max(need, p);
	}

	// ------------------------------------------------------------------ writing

	void write() {
		Noise dressNoise = shaper.noise.fork(0x0D7E55);
		for (int lz = 0; lz < 16; lz++) {
			for (int lx = 0; lx < 16; lx++) {
				int i = (lx + m) + (lz + m) * n;
				if (touched[i]) {
					writeColumn(i, x0 + lx, z0 + lz, dressNoise);
				}
			}
		}
	}

	private int groundEstimate(int i) {
		return g[i];
	}

	private void writeColumn(int i, int x, int z, Noise dressNoise) {
		int gi = g[i];
		int wi = w[i];
		int oi = o[i];
		boolean full = shaper.fullSurface();
		double dressT = 0.22 + 0.22 * dressNoise.fbm01(x, z, 40, 2);
		boolean usePalette = !full || f[i] >= dressT || (tFlags[i] & Column.FORCE_DRESS) != 0;
		boolean waterChanged = wi != ow[i] || tKeep[i] != NONE;
		boolean fall = (tFlags[i] & Column.FALL) != 0 && tFallTop[i] != NONE;
		if (gi == oi && !waterChanged && tSurface[i] == null && !fall && !(full && usePalette)) {
			return;
		}
		BlockState waterState = palette.fluid().orElse(Blocks.WATER.defaultBlockState());
		BlockState air = Blocks.AIR.defaultBlockState();

		// 1) above ground: air, water, waterfall (a source block on top keeps the fall running)
		int hi = Math.max(top[i], Math.max(gi, wi == NONE ? gi : wi - 1));
		if (fall) {
			hi = Math.max(hi, tFallTop[i]);
		}
		int fallBottom = wi != NONE ? wi : gi + 1;
		for (int y = hi; y > gi; y--) {
			BlockState want;
			if (fall && y <= tFallTop[i] && y >= fallBottom) {
				want = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, y == tFallTop[i] ? 0 : 8);
			} else if (wi != NONE && y < wi) {
				want = waterState;
			} else {
				want = air;
			}
			set(x, y, z, want);
		}
		if (fall) {
			pos.set(x, tFallTop[i], z);
			level.scheduleTick(pos.immutable(), Fluids.WATER, 5);
		}

		// 2) ground: dress, fill, cliff faces, seal
		boolean wet = wi != NONE && wi - 1 > gi;
		List<FieldPalette.Layer> layers = usePalette ? (wet ? palette.underwater().orElse(null) : palette.top().orElse(null)) : null;
		if (layers == null && usePalette && wet && palette.top().isPresent()) {
			layers = palette.top().get();
			wetSafe = true;
		} else {
			wetSafe = false;
		}
		BlockState autoTop = origTop[i];
		BlockState autoSub = origSub[i];
		if (wet && (layers == null || wetSafe)) {
			autoTop = underwaterSafe(autoTop);
			autoSub = underwaterSafe(autoSub);
		}
		int topDepth = layers == null ? 4 : depthOf(layers);
		int minNb = Math.min(Math.min(groundEstimate(i - 1), groundEstimate(i + 1)), Math.min(groundEstimate(i - n), groundEstimate(i + n)));
		boolean cliff = gi - minNb >= cliffSlope;
		StrataPalette strata = usePalette ? palette.strata().orElse(null) : null;
		int seal = common.sealDepth();
		int sealBottom = Math.max(minY + 1, Math.max(gi - 64, Math.min(gi - seal, minNb + 1)));
		int bottom = Math.min(sealBottom, gi - topDepth + 1);
		if (gi > oi) {
			bottom = Math.min(bottom, oi - 1);
		}
		int keep = tKeep[i];
		BlockState surface = tSurface[i];
		int surfDepth = tSurfDepth[i];
		int lowestSet = Integer.MAX_VALUE;
		for (int y = gi; y >= bottom; y--) {
			int depth = gi - y;
			pos.set(x, y, z);
			BlockState cur = level.getBlockState(pos);
			if (keep != NONE && y < keep && y > oi) {
				if (cur != waterState) {
					setRaw(waterState);
				}
				continue;
			}
			BlockState want = null;
			if (surface != null && depth < surfDepth && !cliff) {
				want = surface;
			} else if (!cliff && depth < topDepth) {
				want = layerAt(layers, depth, autoTop, autoSub);
				if (depth == 0 && usePalette && !palette.patches().isEmpty()) {
					want = patch(x, z, wet, want);
				}
			} else if (cliff && strata != null && y > minNb) {
				want = strata.strataAt(y);
			} else if (y > oi || (gi > oi && y >= oi - 1)) {
				want = fill(i, y, strata);
			} else if (y >= sealBottom && (cur.isAir() || !cur.getFluidState().isEmpty())) {
				want = fill(i, y, strata);
			}
			if (want != null && want != cur) {
				setRaw(want);
				lowestSet = y;
			}
		}
		// 3) never leave falling blocks over a void
		if (lowestSet != Integer.MAX_VALUE) {
			pos.set(x, lowestSet, z);
			BlockState low = level.getBlockState(pos);
			if (low.getBlock() instanceof FallingBlock) {
				pos.set(x, lowestSet - 1, z);
				BlockState below = level.getBlockState(pos);
				if (below.isAir() || !below.getFluidState().isEmpty()) {
					pos.set(x, lowestSet, z);
					setRaw(support(low));
				}
			}
		}
	}

	private boolean wetSafe;

	private static int depthOf(List<FieldPalette.Layer> layers) {
		int t = 0;
		for (FieldPalette.Layer l : layers) {
			t += l.depth();
		}
		return t;
	}

	private BlockState layerAt(List<FieldPalette.Layer> layers, int depth, BlockState autoTop, BlockState autoSub) {
		if (layers == null) {
			return depth == 0 ? autoTop : autoSub;
		}
		int k = depth;
		for (FieldPalette.Layer l : layers) {
			if (k < l.depth()) {
				return wetSafe ? underwaterSafe(l.block()) : l.block();
			}
			k -= l.depth();
		}
		return layers.getLast().block();
	}

	private BlockState patch(int x, int z, boolean wet, BlockState base) {
		List<FieldPalette.Patch> patches = palette.patches();
		for (int k = 0; k < patches.size(); k++) {
			FieldPalette.Patch p = patches.get(k);
			if (wet != p.underwater()) {
				continue;
			}
			double v = shaper.noise.fork(0x9A7C + k).fbm(x, z, p.scale(), 3);
			if (v > Coverage.threshold(p.coverage(), 3)) {
				return p.block();
			}
		}
		return base;
	}

	private BlockState fill(int i, int y, StrataPalette strata) {
		if (strata != null) {
			return strata.strataAt(y);
		}
		return origDeep[i];
	}

	private static BlockState underwaterSafe(BlockState s) {
		if (s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.PODZOL) || s.is(Blocks.MYCELIUM) || s.is(Blocks.DIRT_PATH)) {
			return Blocks.DIRT.defaultBlockState();
		}
		if (s.is(Blocks.SNOW_BLOCK) || s.is(Blocks.POWDER_SNOW)) {
			return Blocks.GRAVEL.defaultBlockState();
		}
		return s;
	}

	private static BlockState support(BlockState falling) {
		if (falling.is(Blocks.RED_SAND)) {
			return Blocks.RED_SANDSTONE.defaultBlockState();
		}
		if (falling.is(Blocks.GRAVEL) || falling.is(Blocks.SUSPICIOUS_GRAVEL)) {
			return Blocks.STONE.defaultBlockState();
		}
		return Blocks.SANDSTONE.defaultBlockState(); // sand, concrete powder
	}

	private void set(int x, int y, int z, BlockState want) {
		pos.set(x, y, z);
		if (level.getBlockState(pos) != want) {
			setRaw(want);
		}
	}

	private void setRaw(BlockState want) {
		level.setBlock(pos, want, WRITE_FLAGS);
		writes++;
	}
}
