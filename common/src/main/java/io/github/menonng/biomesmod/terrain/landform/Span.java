package io.github.menonng.biomesmod.terrain.landform;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import java.util.List;

/**
 * A number range for landform parameters. JSON: a single number ({@code 12}) or a pair ({@code [8, 20]}).
 * Each landform instance draws its own value from the range, so no two instances look alike.
 */
public record Span(float min, float max) {
	private static final Codec<Span> PAIR = Codec.FLOAT.listOf().comapFlatMap(
			list -> list.size() == 2
					? DataResult.success(new Span(Math.min(list.get(0), list.get(1)), Math.max(list.get(0), list.get(1))))
					: DataResult.error(() -> "range must be a number or [min, max], got " + list),
			span -> List.of(span.min, span.max));

	public static final Codec<Span> CODEC = Codec.either(Codec.FLOAT, PAIR).xmap(
			e -> e.map(v -> new Span(v, v), s -> s),
			s -> s.min == s.max ? Either.left(s.min) : Either.right(s));

	public static Span of(double min, double max) {
		return new Span((float) min, (float) max);
	}

	public static Span of(double v) {
		return new Span((float) v, (float) v);
	}
}
