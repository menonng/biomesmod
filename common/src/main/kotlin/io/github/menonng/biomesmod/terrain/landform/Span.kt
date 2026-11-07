package io.github.menonng.biomesmod.terrain.landform

import com.mojang.datafixers.util.Either
import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import kotlin.math.max
import kotlin.math.min

/**
 * A number range for landform parameters. JSON: a single number (`12`) or a pair (`[8, 20]`). Each landform instance
 * draws its own value from the range, so no two instances look alike.
 */
data class Span(val min: Float, val max: Float) {
	companion object {
		private val PAIR: Codec<Span> = Codec.FLOAT.listOf().comapFlatMap(
			{ list ->
				if (list.size == 2) DataResult.success(Span(min(list[0], list[1]), max(list[0], list[1])))
				else DataResult.error { "range must be a number or [min, max], got $list" }
			},
			{ span -> listOf(span.min, span.max) })

		@JvmField
		val CODEC: Codec<Span> = Codec.either(Codec.FLOAT, PAIR).xmap(
			{ e -> e.map({ v -> Span(v, v) }, { s -> s }) },
			{ s -> if (s.min == s.max) Either.left<Float, Span>(s.min) else Either.right<Float, Span>(s) })

		fun of(min: Double, max: Double): Span = Span(min.toFloat(), max.toFloat())

		fun of(v: Double): Span = Span(v.toFloat(), v.toFloat())
	}
}
