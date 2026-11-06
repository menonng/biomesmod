package io.github.menonng.biomesmod.terrain.landform;

import io.github.menonng.biomesmod.compat.LandformCompat;
import io.github.menonng.biomesmod.compat.LevelCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.material.Fluids;

/**
 * Writes landform columns into ONE chunk. Tracks each column's current terrain top so overlapping
 * instances only add mass. All writes are clipped to the chunk (contract rule 1).
 */
final class ChunkWriter {
	static final BlockState AIR = Blocks.AIR.defaultBlockState();
	static final BlockState WATER = Blocks.WATER.defaultBlockState();
	private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

	final WorldGenLevel level;
	final ChunkGenerator generator;
	final int x0;
	final int z0;
	final int minY;
	final int maxY;
	final int sea;
	/** Current terrain top per column. */
	private final int[] top = new int[256];
	/** Original water surface per column (Col.NONE if dry). */
	private final int[] water = new int[256];
	private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
	int writes;

	ChunkWriter(WorldGenLevel level, ChunkGenerator generator, int x0, int z0, int sea) {
		this.level = level;
		this.generator = generator;
		this.x0 = x0;
		this.z0 = z0;
		this.sea = sea;
		this.minY = LevelCompat.minY(level);
		this.maxY = LandformCompat.maxY(level);
		// Finished chunks (dev showcase) only keep the final heightmaps; worldgen chunks have the _WG ones.
		Heightmap.Types floorType = level.getChunk(x0 >> 4, z0 >> 4) instanceof LevelChunk
				? Heightmap.Types.OCEAN_FLOOR : Heightmap.Types.OCEAN_FLOOR_WG;
		for (int dz = 0; dz < 16; dz++) {
			for (int dx = 0; dx < 16; dx++) {
				int x = x0 + dx;
				int z = z0 + dz;
				int y = level.getHeight(floorType, x, z) - 1;
				while (y > minY && !isTerrain(get(x, y, z))) {
					y--;
				}
				int i = dz * 16 + dx;
				top[i] = y;
				water[i] = Col.NONE;
				if (isWater(get(x, y + 1, z))) {
					int s;
					if (y + 1 <= sea && isWater(get(x, sea, z)) && !isWater(get(x, sea + 1, z))) {
						s = sea;
					} else {
						s = y + 1;
						while (s + 1 < maxY && isWater(get(x, s + 1, z))) {
							s++;
						}
					}
					water[i] = s;
				}
			}
		}
	}

	static int gridIndex(int dx, int dz) {
		return (dz + 1) * 18 + (dx + 1);
	}

	static boolean isTerrain(BlockState s) {
		return s.blocksMotion() && !s.is(BlockTags.LEAVES) && !s.is(BlockTags.LOGS);
	}

	static boolean isWater(BlockState s) {
		return s.getFluidState().is(FluidTags.WATER);
	}

	boolean inside(int x, int z) {
		return x >= x0 && x < x0 + 16 && z >= z0 && z < z0 + 16;
	}

	int top(int x, int z) {
		return top[(z - z0) * 16 + (x - x0)];
	}

	int waterSurface(int x, int z) {
		return water[(z - z0) * 16 + (x - x0)];
	}

	BlockState get(int x, int y, int z) {
		return level.getBlockState(pos.set(x, y, z));
	}

	void set(int x, int y, int z, BlockState s) {
		if (y < minY || y >= maxY || !inside(x, z)) {
			return;
		}
		level.setBlock(pos.set(x, y, z), s, FLAGS);
		writes++;
	}

	private BlockState mat(Landform lf, Col c, int x, int y, int z, int depth, boolean wet) {
		if (c.cap && lf.cap != null && !wet) {
			if (depth == 0) {
				return lf.cap.block();
			}
			if (depth < lf.cap.depth()) {
				return lf.cap.under();
			}
		}
		return lf.block(x, y, z, depth, c, wet);
	}

	void apply(Landform lf, int x, int z, Col c) {
		int i = (z - z0) * 16 + (x - x0);
		int g = top[i];
		BlockState fluid = c.fluid != null ? c.fluid : WATER;
		if (c.top != Col.NONE) {
			int t = Math.min(Math.max(c.top, minY + 1), maxY - 2);
			int fluidTop = Math.max(c.water, water[i]);
			boolean wet = t < fluidTop;
			if (t > g) {
				for (int y = g + 1; y <= t; y++) {
					set(x, y, z, mat(lf, c, x, y, z, t - y, wet));
				}
				top[i] = t;
			} else if (t < g && c.carve) {
				for (int y = t + 1; y <= g; y++) {
					set(x, y, z, y <= fluidTop ? (y <= c.water ? fluid : WATER) : AIR);
				}
				top[i] = t;
				int p = Math.max(1, c.paint);
				for (int y = t - p + 1; y <= t; y++) {
					set(x, y, z, mat(lf, c, x, y, z, t - y, wet));
				}
			} else if (c.paint > 0) {
				for (int y = g - c.paint + 1; y <= g; y++) {
					set(x, y, z, mat(lf, c, x, y, z, g - y, g < fluidTop));
				}
			}
		}
		if (c.water != Col.NONE) {
			for (int y = top[i] + 1; y <= c.water; y++) {
				set(x, y, z, fluid);
			}
		}
		if (c.voidLo != Col.NONE && c.voidHi >= c.voidLo) {
			int hi = Math.min(c.voidHi, top[i] - 1);
			for (int y = c.voidLo; y <= hi; y++) {
				set(x, y, z, y <= c.voidWater ? WATER : AIR);
			}
		}
	}

	/** Makes [fromY, toY] of a basin wall column solid wherever it is open (air or plants), so water cannot leak. */
	void seal(Landform lf, int x, int z, int fromY, int toY) {
		int i = (z - z0) * 16 + (x - x0);
		for (int y = fromY; y <= toY; y++) {
			BlockState s = get(x, y, z);
			if (s.isAir() || (!isTerrain(s) && s.getFluidState().isEmpty())) {
				set(x, y, z, lf.sealBlock(x, y, z));
			}
		}
		if (toY > top[i]) {
			top[i] = toY;
		}
	}

	void setTop(int x, int z, int y) {
		top[(z - z0) * 16 + (x - x0)] = y;
	}

	void scheduleWaterTick(int x, int y, int z) {
		if (inside(x, z)) {
			level.scheduleTick(pos.set(x, y, z).immutable(), Fluids.WATER, 1);
		}
	}

	void placeFeature(Holder<PlacedFeature> feature, int x, int y, int z, long seed) {
		feature.value().place(level, generator, RandomSource.create(seed), new BlockPos(x, y, z));
	}
}
