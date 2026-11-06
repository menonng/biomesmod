package io.github.menonng.biomesmod.terrain.landform;

import io.github.menonng.biomesmod.terrain.util.StrataPalette;
import java.util.List;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One placed landform instance. Built once per grid cell (cached), then asked by every chunk it overlaps to
 * write that chunk's part. {@link #column} must be a pure function of (instance, x, z) so chunk borders agree.
 */
public abstract class Landform {
	static final int K_BODY = 0;
	static final int K_TALUS = 1;
	static final int K_PEDESTAL = 2;
	static final int K_FLOOR = 3;
	static final int K_SEAL = 9;

	final LandformConfig config;
	final int cx;
	final int cz;
	final int reach;
	final long seed;
	final int sea;
	final int minY;
	final int maxY;
	final StrataPalette palette;
	final List<BlockState> talus;
	final List<BlockState> surface;
	final Cap cap;
	/** Holds water: evaluate a one-column border around each chunk and seal basin walls. */
	boolean holdsWater;

	Landform(Site s, int reach) {
		this.config = s.config;
		this.cx = s.x;
		this.cz = s.z;
		this.reach = Math.min(reach, s.config.shape().maxReach());
		this.seed = s.seed;
		this.sea = s.sea;
		this.minY = s.minY;
		this.maxY = s.maxY;
		this.palette = s.config.palette();
		this.talus = s.config.talus();
		this.surface = s.config.surfaceBlocks;
		this.cap = s.config.cap().orElse(null);
	}

	/** Pure per-column geometry. */
	abstract void column(int x, int z, Col c);

	public int centerX() {
		return cx;
	}

	public int centerZ() {
		return cz;
	}

	/** Max horizontal distance from the centre this instance writes to. */
	public int reach() {
		return reach;
	}

	/** A short description for logs (key parameters of this instance). */
	public String describe() {
		return getClass().getSimpleName() + " at " + cx + "," + cz;
	}

	boolean overlaps(int x0, int z0, int x1, int z1) {
		return cx + reach >= x0 && cx - reach <= x1 && cz + reach >= z0 && cz - reach <= z1;
	}

	/** Writes this instance's part of the writer's chunk. */
	final void write(ChunkWriter w) {
		int b = holdsWater ? 1 : 0;
		int xa = Math.max(w.x0 - b, cx - reach);
		int xb = Math.min(w.x0 + 15 + b, cx + reach);
		int za = Math.max(w.z0 - b, cz - reach);
		int zb = Math.min(w.z0 + 15 + b, cz + reach);
		if (xa > xb || za > zb) {
			return;
		}
		Col[] grid = new Col[18 * 18];
		for (int z = za; z <= zb; z++) {
			for (int x = xa; x <= xb; x++) {
				Col c = new Col();
				column(x, z, c);
				grid[ChunkWriter.gridIndex(x - w.x0, z - w.z0)] = c;
			}
		}
		for (int dz = 0; dz < 16; dz++) {
			for (int dx = 0; dx < 16; dx++) {
				Col c = grid[ChunkWriter.gridIndex(dx, dz)];
				if (c != null) {
					w.apply(this, w.x0 + dx, w.z0 + dz, c);
				}
			}
		}
		if (holdsWater) {
			seal(w, grid);
		}
		decorate(w, grid);
	}

	/** Every dry column next to a water column of this instance gets its wall made solid up to that water level. */
	private void seal(ChunkWriter w, Col[] grid) {
		for (int dz = 0; dz < 16; dz++) {
			for (int dx = 0; dx < 16; dx++) {
				Col c = grid[ChunkWriter.gridIndex(dx, dz)];
				if (c != null && (c.isWater() || (c.flag & Col.FLAG_NO_SEAL) != 0)) {
					continue;
				}
				int need = Col.NONE;
				int low = Integer.MAX_VALUE;
				for (int k = 0; k < 4; k++) {
					int nx = dx + (k == 0 ? 1 : k == 1 ? -1 : 0);
					int nz = dz + (k == 2 ? 1 : k == 3 ? -1 : 0);
					Col n = grid[ChunkWriter.gridIndex(nx, nz)];
					if (n != null && n.isWater()) {
						need = Math.max(need, n.water);
						low = Math.min(low, n.top + 1);
					}
				}
				if (need != Col.NONE) {
					w.seal(this, w.x0 + dx, w.z0 + dz, low, need);
				}
			}
		}
	}

	/** Material of a block that is {@code depth} blocks below the column's new top. */
	BlockState block(int x, int y, int z, int depth, Col c, boolean wet) {
		if (c.kind == K_TALUS) {
			return talusBlock(x, y, z);
		}
		return strata(x, y, z, depth, wet);
	}

	/** Extra blocks after all columns are written (trees, falls, vents ...). Writes only inside the chunk. */
	void decorate(ChunkWriter w, Col[] grid) {
		placeCapFeatures(w, grid);
	}

	// ---- material helpers ----

	BlockState strata(int x, int y, int z, int depth, boolean wet) {
		if (depth == 0 && !wet && !surface.isEmpty()) {
			return pick(surface, x, y, z, 0x5F1);
		}
		if (wet) {
			return palette.strataAt(y);
		}
		return palette.blockAt(y, depth);
	}

	BlockState talusBlock(int x, int y, int z) {
		return pick(talus, x, y, z, 0x7A1);
	}

	/** Patchy choice from a list: clustered by low-frequency noise, speckled by a per-block hash. */
	BlockState pick(List<BlockState> list, int x, int y, int z, long salt) {
		int n = list.size();
		if (n == 1) {
			return list.getFirst();
		}
		double v = 0.5 + 0.42 * LNoise.noise2(seed ^ salt, x / 6.5, z / 6.5) + 0.35 * (LNoise.unit(seed ^ salt, x, y, z) - 0.5);
		int i = (int) Math.floor(v * n);
		return list.get(Math.max(0, Math.min(n - 1, i)));
	}

	BlockState sealBlock(int x, int y, int z) {
		return palette.strataAt(y);
	}

	void placeCapFeatures(ChunkWriter w, Col[] grid) {
		if (cap == null || cap.feature().isEmpty() || cap.chance() <= 0) {
			return;
		}
		for (int dz = 0; dz < 16; dz++) {
			for (int dx = 0; dx < 16; dx++) {
				Col c = grid[ChunkWriter.gridIndex(dx, dz)];
				if (c == null || !c.cap) {
					continue;
				}
				int x = w.x0 + dx;
				int z = w.z0 + dz;
				long h = LNoise.hash(seed ^ 0x7EE5, x, z);
				if (LNoise.unit(h) < cap.chance()) {
					int y = w.top(x, z);
					if (y >= sea) {
						w.placeFeature(cap.feature().get(), x, y + 1, z, h);
					}
				}
			}
		}
	}

	/**
	 * Top of an eroded vertical wall at normalised distance {@code q} (1 = nominal outline): solid at y while
	 * q <= 1 + band * n(y) - taper * t(y). Scanning from the bottom and stopping at the first miss keeps walls
	 * free of floating overhangs; horizontal bands give ledges and notches like real strata.
	 */
	int erodedTop(double q, int y0, int y1, double band, double taper, long bandSeed, double bandScale) {
		if (q <= 1 - band - taper) {
			return y1;
		}
		if (q > 1 + band) {
			return y0 - 1;
		}
		int h = Math.max(1, y1 - y0);
		for (int y = y0; y <= y1; y++) {
			double t = (y - y0) / (double) h;
			double lim = 1 + band * LNoise.noise1(bandSeed, y / bandScale) - taper * t;
			if (q > lim) {
				return y - 1;
			}
		}
		return y1;
	}
}
