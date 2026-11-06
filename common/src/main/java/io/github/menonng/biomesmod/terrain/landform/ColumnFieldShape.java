package io.github.menonng.biomesmod.terrain.landform;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.menonng.biomesmod.terrain.util.StrataPalette;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code column_field}: a field of polygonal basalt columns of varied heights that steps down from the land into the
 * sea (Giant's Causeway). Columns are Voronoi cells (mostly 5-7 sided at this scale), each with its own height; the
 * field is an irregular lobe that runs downhill and out under the water.
 *
 * @param cell     column width in blocks (2-3 reads best)
 * @param length   how far the lobe runs (blocks)
 * @param width    lobe width
 * @param rise     height of the landward end above sea level
 * @param jitter   per-column height variation (blocks)
 */
public final class ColumnFieldShape extends LandformShape {
	static final String NAME = "column_field";

	static final MapCodec<ColumnFieldShape> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Codec.floatRange(1.2f, 6).optionalFieldOf("cell", 2.2f).forGetter(s -> s.cell),
			Span.CODEC.optionalFieldOf("length", Span.of(36, 60)).forGetter(s -> s.length),
			Span.CODEC.optionalFieldOf("width", Span.of(16, 26)).forGetter(s -> s.width),
			Span.CODEC.optionalFieldOf("rise", Span.of(6, 12)).forGetter(s -> s.rise),
			Codec.floatRange(0, 6).optionalFieldOf("jitter", 2f).forGetter(s -> s.jitter)
	).apply(i, ColumnFieldShape::new));

	final float cell;
	final Span length;
	final Span width;
	final Span rise;
	final float jitter;

	ColumnFieldShape(float cell, Span length, Span width, Span rise, float jitter) {
		this.cell = cell;
		this.length = length;
		this.width = width;
		this.rise = rise;
		this.jitter = jitter;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	int maxReach() {
		return (int) Math.ceil(length.max() * 0.6 + width.max() + 4);
	}

	@Override
	StrataPalette defaultPalette() {
		return B.strata("polished_basalt", 1, "basalt", 4, "smooth_basalt", 1);
	}

	@Override
	Span defaultBaseRange() {
		return Span.of(-6, 6); // at the shoreline
	}

	@Override
	Landform create(Site site) {
		LNoise.Rng r = site.rng;
		// Run towards the lowest neighbouring floor: down the beach into the sea.
		int best = Integer.MAX_VALUE;
		double dir = 0;
		for (int k = 0; k < 8; k++) {
			double a = Math.PI * 2 * k / 8;
			int f = site.floor(site.x + Math.cos(a) * 30, site.z + Math.sin(a) * 30);
			if (f < best) {
				best = f;
				dir = a;
			}
		}
		if (best >= site.sea - 1) {
			return null; // needs water on one side
		}
		return new Instance(site, dir + r.range(-0.3, 0.3), r.range(length), r.range(width), (int) r.range(rise));
	}

	final class Instance extends Landform {
		final double dir;
		final double len;
		final double wid;
		final int rise;
		final double[] w = new double[6];

		Instance(Site s, double dir, double len, double wid, int rise) {
			super(s, maxReach());
			this.dir = dir;
			this.len = len;
			this.wid = wid;
			this.rise = rise;
		}

		@Override
		void column(int x, int z, Col c) {
			double px = x + 0.5;
			double pz = z + 0.5;
			double dx = px - cx;
			double dz = pz - cz;
			double u = dx * Math.cos(dir) + dz * Math.sin(dir);    // seaward
			double v = -dx * Math.sin(dir) + dz * Math.cos(dir);
			double t = (u + len * 0.4) / len;                      // 0 landward end, 1 seaward tip
			if (t < 0 || t > 1) {
				return;
			}
			double halfW = wid * 0.5 * Math.sin(Math.PI * Math.min(1, t * 1.3 + 0.1)) * (1 + 0.3 * LNoise.noise1(seed ^ 0xB1, u / 12));
			if (Math.abs(v) > halfW) {
				return;
			}
			// One height per column (cell); cells via jittered grid.
			double gx = px / cell;
			double gz = pz / cell;
			int ci = LNoise.floor(gx);
			int cj = LNoise.floor(gz);
			double bestD = Double.MAX_VALUE;
			long cellHash = 0;
			for (int dj = -1; dj <= 1; dj++) {
				for (int di = -1; di <= 1; di++) {
					long h = LNoise.hash(seed ^ 0xB2, ci + di, cj + dj);
					double fx = ci + di + 0.5 + (LNoise.unit(h) - 0.5) * 0.8;
					double fz = cj + dj + 0.5 + (LNoise.unit(h * 31 + 7) - 0.5) * 0.8;
					double dd = (fx - gx) * (fx - gx) + (fz - gz) * (fz - gz);
					if (dd < bestD) {
						bestD = dd;
						cellHash = h;
					}
				}
			}
			double edgeFade = Math.min(1, (halfW - Math.abs(v)) / 4);
			double level = sea - 2 + (rise + 2) * Math.pow(1 - t, 1.3) * edgeFade;
			int top = (int) Math.round(level + jitter * (LNoise.unit(cellHash * 17 + 3) - 0.5) * 2);
			c.top = top;
			c.kind = K_BODY;
		}

		@Override
		BlockState block(int x, int y, int z, int depth, Col col, boolean wet) {
			if (depth == 0) {
				return Blocks.POLISHED_BASALT.defaultBlockState();
			}
			return Blocks.BASALT.defaultBlockState();
		}
	}
}
