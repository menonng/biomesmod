package io.github.menonng.biomesmod.terrain.landform;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

/**
 * {@code biomesmod:umbrella_tree}: a tree whose trunk forks into many upward branches carrying one dense, flat-topped
 * umbrella crown (Socotra dragon's blood tree). {@code twist} &gt; 0 leans and kinks the trunk instead, for flat-crowned
 * savanna trees. Place it with ordinary placement modifiers (count, in_square, heightmap, biome); it only grows on
 * dirt-like ground and never overwrites solid blocks.
 */
public final class UmbrellaTreeFeature extends Feature<UmbrellaTreeFeature.Config> {
	public record Config(BlockState trunk, BlockState leaves, Span trunkHeight, Span crownRadius, Span crownThickness, int branches,
			float twist) implements FeatureConfiguration {
		public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
				BlockState.CODEC.optionalFieldOf("trunk", B.of("dark_oak_log")).forGetter(Config::trunk),
				BlockState.CODEC.optionalFieldOf("leaves", B.of("dark_oak_leaves")).forGetter(Config::leaves),
				Span.CODEC.optionalFieldOf("trunk_height", Span.of(3, 5)).forGetter(Config::trunkHeight),
				Span.CODEC.optionalFieldOf("crown_radius", Span.of(3.5, 5.5)).forGetter(Config::crownRadius),
				Span.CODEC.optionalFieldOf("crown_thickness", Span.of(2, 3)).forGetter(Config::crownThickness),
				Codec.intRange(2, 12).optionalFieldOf("branches", 6).forGetter(Config::branches),
				Codec.floatRange(0, 1).optionalFieldOf("twist", 0f).forGetter(Config::twist)
		).apply(i, Config::new));
	}

	public UmbrellaTreeFeature() {
		super(Config.CODEC);
	}

	@Override
	public boolean place(FeaturePlaceContext<Config> context) {
		WorldGenLevel level = context.level();
		RandomSource r = context.random();
		Config cfg = context.config();
		BlockPos origin = context.origin();
		BlockState ground = level.getBlockState(origin.below());
		if (!ground.is(BlockTags.DIRT) && !ground.is(BlockTags.SAND)) {
			return false;
		}
		int h = (int) Math.round(cfg.trunkHeight().min() + r.nextFloat() * (cfg.trunkHeight().max() - cfg.trunkHeight().min()));
		double cr = cfg.crownRadius().min() + r.nextFloat() * (cfg.crownRadius().max() - cfg.crownRadius().min());
		int ct = (int) Math.round(cfg.crownThickness().min() + r.nextFloat() * (cfg.crownThickness().max() - cfg.crownThickness().min()));
		BlockState log = cfg.trunk();
		BlockState leaves = cfg.leaves();
		BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

		// Trunk (optionally leaning and kinked).
		double tx = origin.getX();
		double tz = origin.getZ();
		double lean = r.nextFloat() * Math.PI * 2;
		for (int y = 0; y < h; y++) {
			if (cfg.twist() > 0 && y > 0 && r.nextFloat() < cfg.twist() * 0.6) {
				tx += Math.cos(lean);
				tz += Math.sin(lean);
			}
			set(level, p.set((int) Math.round(tx), origin.getY() + y, (int) Math.round(tz)), log);
		}
		int forkY = origin.getY() + h;
		int crownBase = forkY + Math.max(2, (int) Math.round(cr * 0.6));
		int cx = (int) Math.round(tx);
		int cz = (int) Math.round(tz);

		// Branches: fan out and up from the fork to just under the crown.
		int n = cfg.branches();
		double phase = r.nextFloat() * Math.PI * 2;
		for (int b = 0; b < n; b++) {
			double a = phase + Math.PI * 2 * b / n + (r.nextFloat() - 0.5) * 0.5;
			double reach = cr * (0.55 + 0.3 * r.nextFloat());
			int steps = Math.max(2, crownBase - forkY);
			for (int s = 0; s <= steps; s++) {
				double t = s / (double) steps;
				int bx = cx + (int) Math.round(Math.cos(a) * reach * t);
				int bz = cz + (int) Math.round(Math.sin(a) * reach * t);
				set(level, p.set(bx, forkY + s - 1, bz), log);
			}
		}

		// Crown: a dense, flat-topped dome, slightly ragged at the rim.
		int ri = (int) Math.ceil(cr);
		for (int dy = 0; dy < ct; dy++) {
			double layerR = cr * (dy == ct - 1 ? 0.85 : 1.0);
			for (int dz = -ri; dz <= ri; dz++) {
				for (int dx = -ri; dx <= ri; dx++) {
					double d = Math.sqrt(dx * dx + dz * dz);
					if (d > layerR + (r.nextFloat() - 0.5) * 0.8) {
						continue;
					}
					set(level, p.set(cx + dx, crownBase + dy, cz + dz), leaves);
				}
			}
		}
		return true;
	}

	private static void set(WorldGenLevel level, BlockPos pos, BlockState state) {
		BlockState cur = level.getBlockState(pos);
		if (cur.isAir() || cur.canBeReplaced() || cur.is(BlockTags.LEAVES)) {
			level.setBlock(pos, state, 19);
		}
	}
}
