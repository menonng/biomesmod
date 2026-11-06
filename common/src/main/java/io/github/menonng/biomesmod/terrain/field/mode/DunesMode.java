package io.github.menonng.biomesmod.terrain.field.mode;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.menonng.biomesmod.terrain.field.ChunkFrame;
import io.github.menonng.biomesmod.terrain.field.Column;
import io.github.menonng.biomesmod.terrain.field.Common;
import io.github.menonng.biomesmod.terrain.field.Coverage;
import io.github.menonng.biomesmod.terrain.field.FieldPalette;
import io.github.menonng.biomesmod.terrain.field.Noise;
import io.github.menonng.biomesmod.terrain.field.Shaper;
import io.github.menonng.biomesmod.terrain.field.ShaperMode;
import java.util.Optional;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * {@code dunes}: a dune sea over a flat interdune floor.
 * <ul>
 * <li>{@code transverse}: sinuous crest lines across the wind with a gentle windward side and a steep slip face
 * (Lençóis Maranhenses). Crests break up and rejoin, they are never continuous rings or crescents.</li>
 * <li>{@code linear}: long seif ridges along the wind, symmetric.</li>
 * <li>{@code star}: tall pyramidal dunes with radiating arms (Namib / Sossusvlei).</li>
 * </ul>
 * {@code lagoon_depth} floods the interdune floor up to that height, so rain lagoons fill every hollow between crests.
 * {@code pan_coverage} flattens patches into clay pans of {@code pan_block} (Deadvlei).
 *
 * @param level      absolute interdune floor Y; default max(sea + 1, regional terrain height) + base_offset
 */
public record DunesMode(Common common, String style, double height, double wavelength, double direction, Optional<Integer> level,
		int baseOffset, int lagoonDepth, double panCoverage, Optional<BlockState> panBlock) implements ShaperMode {
	public static final MapCodec<DunesMode> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Common.MAP_CODEC.forGetter(DunesMode::common),
			Codec.STRING.optionalFieldOf("style", "transverse").forGetter(DunesMode::style),
			Codec.doubleRange(1, 120).optionalFieldOf("height", 10.0).forGetter(DunesMode::height),
			Codec.doubleRange(8, 1024).optionalFieldOf("wavelength", 40.0).forGetter(DunesMode::wavelength),
			Codec.doubleRange(-360, 360).optionalFieldOf("direction", 20.0).forGetter(DunesMode::direction),
			Codec.INT.optionalFieldOf("level").forGetter(DunesMode::level),
			Codec.intRange(-32, 64).optionalFieldOf("base_offset", 0).forGetter(DunesMode::baseOffset),
			Codec.intRange(0, 12).optionalFieldOf("lagoon_depth", 0).forGetter(DunesMode::lagoonDepth),
			Codec.doubleRange(0, 1).optionalFieldOf("pan_coverage", 0.0).forGetter(DunesMode::panCoverage),
			BlockState.CODEC.optionalFieldOf("pan_block").forGetter(DunesMode::panBlock)
	).apply(i, DunesMode::new));

	@Override
	public Type type() {
		return Type.DUNES;
	}

	@Override
	public Shaper createShaper(long seed) {
		return new S(seed);
	}

	private final class S extends Shaper {
		private final double cos = Math.cos(Math.toRadians(direction));
		private final double sin = Math.sin(Math.toRadians(direction));
		private final double panT = Coverage.threshold(panCoverage, 3);
		private final BlockState pan = panBlock.orElse(P.id("white_terracotta"));

		S(long seed) {
			super(seed);
		}

		@Override
		public FieldPalette defaultPalette() {
			return P.palette(P.layers(Blocks.SAND, 4, Blocks.SANDSTONE, 3), P.layers(Blocks.SAND, 3),
					P.strata(Blocks.SANDSTONE, 3, Blocks.SMOOTH_SANDSTONE, 1), 1024);
		}

		@Override
		public void prepare(ChunkFrame frame) {
			frame.prepareBase(level.isPresent() ? 0 : 6);
		}

		private double floor(ChunkFrame frame, int x, int z) {
			if (level.isPresent()) {
				return level.get() + baseOffset;
			}
			return Math.max(frame.seaLevel + 1, Math.floor(frame.blurBase(x, z) + 0.5)) + baseOffset;
		}

		/** Dune height above the interdune floor, >= 0. */
		private double dune(double x, double z) {
			double u = x * cos + z * sin;   // along the wind
			double v = -x * sin + z * cos;  // across the wind
			double lam = wavelength;
			double amp = height * (0.55 + 0.45 * noise.fbm01(x, z, lam * 3.5, 2));
			switch (style) {
				case "star" -> {
					double r = noise.ridged(x, z, lam, 3);
					double peak = noise.fbm01(x + 701, z - 409, lam * 0.9, 2);
					return amp * Math.pow(r, 2.2) * (0.35 + 0.65 * peak);
				}
				case "linear" -> {
					double p = v / lam + 0.45 * noise.fbm(u / (lam * 6), v / (lam * 3), 1, 2);
					double fr = p - Math.floor(p);
					double prof = 1 - Math.abs(fr - 0.5) * 2;
					double run = Noise.smoothstep(-0.35, 0.15, noise.fbm(u, v, lam * 5, 2));
					return amp * Math.pow(prof, 1.6) * run;
				}
				default -> {
					double p = u / lam + 0.55 * noise.fbm(v / (lam * 2.2), u / (lam * 4.0), 1, 2);
					double fr = p - Math.floor(p);
					double a = 0.72; // windward share of the wavelength
					double prof = fr < a ? fr / a : (1 - fr) / (1 - a);
					prof = prof * prof * (3 - 2 * prof);
					double breakup = Noise.smoothstep(-0.85, -0.25, noise.fbm(x - 1201, z + 557, lam * 1.7, 2));
					return amp * prof * breakup;
				}
			}
		}

		@Override
		public void column(ChunkFrame frame, int x, int z, int i, Column out) {
			double base = floor(frame, x, z);
			// Interdune floor relief: only the deeper hollows hold lagoons, the rest is damp sand.
			double floorRelief = lagoonDepth > 0 ? 1.0 + 1.6 * noise.fbm(x + 77, z - 91, wavelength * 1.3, 2) : 0;
			double h = dune(x, z) + Math.max(0, floorRelief);
			if (panCoverage > 0) {
				double pm = noise.fbm(x + 2203, z + 3109, wavelength * 4, 3);
				double t = Noise.smoothstep(panT, panT + 0.12, pm);
				h *= 1 - t;
				if (t > 0.6) {
					out.surface = pan;
					out.surfaceDepth = 2;
					base -= 1;
				}
			}
			out.ground = base + h;
			if (lagoonDepth > 0) {
				out.water = (int) Math.floor(base + 0.5) + lagoonDepth;
			}
		}
	}
}
