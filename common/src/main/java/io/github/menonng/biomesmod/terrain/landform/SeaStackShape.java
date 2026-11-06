package io.github.menonng.biomesmod.terrain.landform;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.menonng.biomesmod.terrain.util.StrataPalette;
import java.util.List;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code sea_stack}: a cluster of stacks, stumps and the occasional arch standing in shallow water off a coast
 * (Cliffs of Moher, Lofoten, Ha Long). Each stack rises from the sea floor with a wave-cut notch near the waterline,
 * banded walls and a ragged, sometimes grassy top. Only placed where the floor is under shallow water.
 */
public final class SeaStackShape extends LandformShape {
	static final String NAME = "sea_stack";

	static final MapCodec<SeaStackShape> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Span.CODEC.optionalFieldOf("cluster_radius", Span.of(18, 34)).forGetter(s -> s.clusterRadius),
			Codec.intRange(1, 24).optionalFieldOf("count", 6).forGetter(s -> s.count),
			Span.CODEC.optionalFieldOf("stack_radius", Span.of(2, 6)).forGetter(s -> s.stackRadius),
			Span.CODEC.optionalFieldOf("height", Span.of(8, 30)).forGetter(s -> s.height),
			Codec.floatRange(0, 1).optionalFieldOf("arch_chance", 0.25f).forGetter(s -> s.archChance),
			Codec.floatRange(0, 1).optionalFieldOf("notch", 0.6f).forGetter(s -> s.notch)
	).apply(i, SeaStackShape::new));

	final Span clusterRadius;
	final int count;
	final Span stackRadius;
	final Span height;
	final float archChance;
	final float notch;

	SeaStackShape(Span clusterRadius, int count, Span stackRadius, Span height, float archChance, float notch) {
		this.clusterRadius = clusterRadius;
		this.count = count;
		this.stackRadius = stackRadius;
		this.height = height;
		this.archChance = archChance;
		this.notch = notch;
	}

	@Override
	public String name() {
		return NAME;
	}

	@Override
	int maxReach() {
		return (int) Math.ceil(clusterRadius.max() + stackRadius.max() * 2.5 + 4);
	}

	@Override
	StrataPalette defaultPalette() {
		return B.strata(null, 0, "stone", 3, "andesite", 2, "stone", 2, "cobblestone", 1, "tuff", 1);
	}

	@Override
	List<BlockState> defaultTalus() {
		return B.list("gravel", "cobblestone", "mossy_cobblestone", "andesite");
	}

	@Override
	Span defaultBaseRange() {
		return Span.of(-14, -1); // only in shallow water
	}

	@Override
	int defaultMaxRelief() {
		return 14;
	}

	@Override
	Landform create(Site site) {
		LNoise.Rng r = site.rng;
		double cr = r.range(clusterRadius);
		Instance inst = new Instance(site, count);
		int placed = 0;
		for (int k = 0; k < count * 3 && placed < count; k++) {
			double a = r.angle();
			double d = cr * Math.sqrt(r.next());
			double x = site.x + Math.cos(a) * d;
			double z = site.z + Math.sin(a) * d;
			int f = site.floor(x, z);
			if (f >= site.sea - 1 || f < site.sea - 18) {
				continue; // stacks stand in the shallows only
			}
			double rad = r.range(stackRadius);
			int top = Math.min(site.sea + (int) r.range(height), site.ceiling());
			inst.stack(placed++, x, z, f, rad, top, r.chance(archChance), r.angle());
		}
		return placed == 0 ? null : inst;
	}

	final class Instance extends Landform {
		final double[] sx;
		final double[] sz;
		final double[] sr;
		final int[] sf;
		final int[] st;
		final boolean[] arch;
		final double[] archA;
		int n;

		Instance(Site s, int count) {
			super(s, maxReach());
			sx = new double[count];
			sz = new double[count];
			sr = new double[count];
			sf = new int[count];
			st = new int[count];
			arch = new boolean[count];
			archA = new double[count];
		}

		void stack(int k, double x, double z, int floor, double r, int top, boolean hasArch, double a) {
			sx[k] = x;
			sz[k] = z;
			sf[k] = floor;
			sr[k] = r;
			st[k] = top;
			arch[k] = hasArch && r > 3;
			archA[k] = a;
			n = k + 1;
		}

		@Override
		void column(int x, int z, Col c) {
			double px = x + 0.5;
			double pz = z + 0.5;
			for (int k = 0; k < n; k++) {
				double dx = px - sx[k];
				double dz = pz - sz[k];
				double d = Math.sqrt(dx * dx + dz * dz);
				double a = Math.atan2(dz, dx);
				double rr = sr[k] * (1 + 0.28 * LNoise.angular(seed ^ (0x81 + k), a, 5));
				double reach = rr * (arch[k] ? 2.6 : 1.3);
				if (d > reach) {
					continue;
				}
				int top;
				if (arch[k]) {
					// Elongated fin with a hole through it near the waterline.
					double u = dx * Math.cos(archA[k]) + dz * Math.sin(archA[k]);
					double v = -dx * Math.sin(archA[k]) + dz * Math.cos(archA[k]);
					double q = Math.sqrt((u / (rr * 2.4)) * (u / (rr * 2.4)) + (v / rr) * (v / rr));
					top = erodedTop(q, sf[k], st[k], 0.06, 0.12, seed ^ (0x91 + k), 3);
					if (top < sf[k]) {
						continue;
					}
					if (Math.abs(u) < rr * 0.9) {
						c.voidLo = sf[k] + 1;
						c.voidHi = Math.min(top - 3, sea + (int) (rr * 0.8));
						c.voidWater = sea - 1;
					}
				} else {
					double q = d / rr;
					top = erodedTop(q, sf[k], st[k], 0.07, 0.25, seed ^ (0x91 + k), 3);
					if (top < sf[k]) {
						continue;
					}
					// Wave-cut notch just above the waterline makes the stack slightly top-heavy.
					if (notch > 0 && q > 0.75 - 0.3 * notch && top > sea + 2) {
						c.voidLo = sea - 1;
						c.voidHi = sea;
						c.voidWater = sea - 1;
					}
				}
				top += (int) Math.round(LNoise.noise2(seed ^ 0xA1, px / 3, pz / 3));
				if (c.top == Col.NONE || top > c.top) {
					c.top = top;
					c.kind = K_BODY;
					c.cap = top >= st[k] - 2;
				}
			}
		}
	}
}
