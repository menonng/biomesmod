package io.github.menonng.biomesmod.site;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.menonng.biomesmod.compat.DensityCompat;
import java.util.Objects;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * {@code biomesmod:site}: wraps one of the overworld terrain inputs ({@code offset}, {@code factor} or
 * {@code jaggedness}) and pulls it towards what each site wants. Vanilla terrain, caves, aquifers and surface rules
 * are all built from these three, so a site that asks for flat land at y=205 gets a real plateau, with natural
 * slopes up to it across the ramp.
 *
 * <p>The climate inputs are the same functions the biome source samples, so sites are chosen from the same climate
 * the vanilla biomes see. {@code noise} seeds the site layout and warps the outlines.
 */
public final class SiteDensity implements DensityFunction {
	public static final MapCodec<SiteDensity> DATA_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
			Codec.STRING.fieldOf("mode").forGetter(SiteDensity::mode),
			DensityCompat.FUNCTION.fieldOf("argument").forGetter(SiteDensity::argument),
			DensityFunction.NoiseHolder.CODEC.fieldOf("noise").forGetter(SiteDensity::noise),
			DensityCompat.FUNCTION.fieldOf("temperature").forGetter(SiteDensity::temperature),
			DensityCompat.FUNCTION.fieldOf("vegetation").forGetter(SiteDensity::vegetation),
			DensityCompat.FUNCTION.fieldOf("continents").forGetter(SiteDensity::continents),
			DensityCompat.FUNCTION.fieldOf("erosion").forGetter(SiteDensity::erosion)
	).apply(i, SiteDensity::new));
	public static final KeyDispatchDataCodec<SiteDensity> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

	/** Offset of land at y = 72, the reference that {@code relief} measures hills from. */
	private static final double OFFSET_REF = offsetForY(72);

	private final String mode;
	private final int kind;
	private final DensityFunction argument;
	private final DensityFunction.NoiseHolder noise;
	private final DensityFunction temperature;
	private final DensityFunction vegetation;
	private final DensityFunction continents;
	private final DensityFunction erosion;
	private volatile SiteField field;

	public SiteDensity(String mode, DensityFunction argument, DensityFunction.NoiseHolder noise, DensityFunction temperature,
			DensityFunction vegetation, DensityFunction continents, DensityFunction erosion) {
		this.mode = mode;
		this.kind = switch (mode) {
			case "offset" -> 0;
			case "factor" -> 1;
			case "jaggedness" -> 2;
			default -> throw new IllegalArgumentException("biomesmod:site mode must be offset, factor or jaggedness, not " + mode);
		};
		this.argument = argument;
		this.noise = noise;
		this.temperature = temperature;
		this.vegetation = vegetation;
		this.continents = continents;
		this.erosion = erosion;
	}

	/** Vanilla terrain offset whose surface sits at block height y (depth = 1.5 - (y + 64) / 128 + offset = 0). */
	public static double offsetForY(double y) {
		return (y + 64) / 128.0 - 1.5;
	}

	public String mode() {
		return mode;
	}

	public DensityFunction argument() {
		return argument;
	}

	public DensityFunction.NoiseHolder noise() {
		return noise;
	}

	public DensityFunction temperature() {
		return temperature;
	}

	public DensityFunction vegetation() {
		return vegetation;
	}

	public DensityFunction continents() {
		return continents;
	}

	public DensityFunction erosion() {
		return erosion;
	}

	/** The world's site field; only valid once the noise is wired (inside a RandomState). */
	SiteField field() {
		SiteField f = field;
		if (f == null) {
			f = SiteField.of(this);
			field = f;
		}
		return f;
	}

	@Override
	public double compute(DensityFunction.FunctionContext context) {
		double orig = argument.compute(context);
		if (noise.noise() == null) {
			return orig;
		}
		SiteField.Blend b = field().blend(context.blockX(), context.blockZ());
		double total = b.total();
		if (total <= 0) {
			return orig;
		}
		double out = orig * (1 - total) + b.wa() * target(b.a().spec(), orig);
		if (b.b() != null) {
			out += b.wb() * target(b.b().spec(), orig);
		}
		return out;
	}

	private double target(SiteConfig.Spec s, double orig) {
		SiteConfig.Terrain t = s.terrain();
		if (t == SiteConfig.Terrain.KEEP) {
			return orig;
		}
		return switch (kind) {
			case 0 -> {
				double lifted = offsetForY(s.targetY()) + s.relief() * (orig - OFFSET_REF);
				yield t == SiteConfig.Terrain.MOUNTAIN ? Math.max(orig, lifted) : lifted;
			}
			case 1 -> switch (t) {
				case PLATEAU -> Math.max(orig, 12.0);
				case BASIN -> Math.max(orig, 9.0);
				default -> orig;
			};
			default -> switch (t) {
				case PLATEAU, BASIN -> 0.0;
				case LIFT -> orig * s.relief();
				default -> orig;
			};
		};
	}

	@Override
	public void fillArray(double[] array, DensityFunction.ContextProvider provider) {
		provider.fillAllDirectly(array, this);
	}

	/** 1.21.1 visitor entry point (26.2 has a default that calls {@link #mapChildren}). */
	public DensityFunction mapAll(DensityFunction.Visitor visitor) {
		return visitor.apply(new SiteDensity(mode, argument.mapAll(visitor), visitor.visitNoise(noise), temperature.mapAll(visitor),
				vegetation.mapAll(visitor), continents.mapAll(visitor), erosion.mapAll(visitor)));
	}

	/** 26.2: maps the direct children; the visitor recurses. */
	public DensityFunction mapChildren(DensityFunction.Visitor visitor) {
		return new SiteDensity(mode, visitor.apply(argument), visitor.visitNoise(noise), visitor.apply(temperature),
				visitor.apply(vegetation), visitor.apply(continents), visitor.apply(erosion));
	}

	@Override
	public double minValue() {
		return Math.min(argument.minValue(), -2.0);
	}

	@Override
	public double maxValue() {
		return Math.max(argument.maxValue(), 12.0);
	}

	@Override
	public KeyDispatchDataCodec<? extends DensityFunction> codec() {
		return CODEC;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof SiteDensity s && mode.equals(s.mode) && argument.equals(s.argument) && noise.equals(s.noise)
				&& temperature.equals(s.temperature) && vegetation.equals(s.vegetation) && continents.equals(s.continents)
				&& erosion.equals(s.erosion);
	}

	@Override
	public int hashCode() {
		return Objects.hash(mode, argument, noise, temperature, vegetation, continents, erosion);
	}
}
