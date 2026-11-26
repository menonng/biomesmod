package io.github.menonng.biomesmod.terrain.landform

import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.MapCodec

/** Registry of `"shape"` values for `biomesmod:landform`. */
object LandformShapes {
	private val CODECS: Map<String, MapCodec<out LandformShape>> = linkedMapOf(
		PillarForestShape.NAME to PillarForestShape.CODEC,
		ButteShape.NAME to ButteShape.CODEC,
		MonolithShape.NAME to MonolithShape.CODEC,
		ConeShape.NAME to ConeShape.CODEC,
		CalderaShape.NAME to CalderaShape.CODEC,
		SeaStackShape.NAME to SeaStackShape.CODEC,
		KarstIslandShape.NAME to KarstIslandShape.CODEC,
		ColumnFieldShape.NAME to ColumnFieldShape.CODEC,
		HotSpringShape.NAME to HotSpringShape.CODEC,
		CascadeShape.NAME to CascadeShape.CODEC,
		GlacialLakeShape.NAME to GlacialLakeShape.CODEC,
		GeyserShape.NAME to GeyserShape.CODEC,
	)

	private val NAME: Codec<String> = Codec.STRING.flatXmap({ s ->
		if (CODECS.containsKey(s)) DataResult.success(s)
		else DataResult.error { "Unknown landform shape '$s', expected one of ${CODECS.keys}" }
	}, { DataResult.success(it) })

	internal val MAP_CODEC: MapCodec<LandformShape> = io.github.menonng.biomesmod.compat.CodecCompat.dispatchMap(NAME, "shape", { it.name() }, { CODECS[it]!! })

	fun names(): Iterable<String> = CODECS.keys
}
