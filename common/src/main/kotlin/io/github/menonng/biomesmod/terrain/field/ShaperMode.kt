package io.github.menonng.biomesmod.terrain.field

import com.mojang.serialization.Codec
import com.mojang.serialization.DataResult
import com.mojang.serialization.MapCodec
import io.github.menonng.biomesmod.terrain.field.mode.CanyonMode
import io.github.menonng.biomesmod.terrain.field.mode.DepressionMode
import io.github.menonng.biomesmod.terrain.field.mode.DunesMode
import io.github.menonng.biomesmod.terrain.field.mode.FlattenMode
import io.github.menonng.biomesmod.terrain.field.mode.GlacierMode
import io.github.menonng.biomesmod.terrain.field.mode.IceSheetMode
import io.github.menonng.biomesmod.terrain.field.mode.IslandsMode
import io.github.menonng.biomesmod.terrain.field.mode.RollingMode
import io.github.menonng.biomesmod.terrain.field.mode.SeaCliffMode
import io.github.menonng.biomesmod.terrain.field.mode.TerracesMode
import io.github.menonng.biomesmod.terrain.field.mode.UValleyMode
import io.github.menonng.biomesmod.terrain.field.mode.WetlandMode
import io.github.menonng.biomesmod.terrain.field.mode.YardangsMode
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration

/**
 * Config of `biomesmod:terrain_shaper`: a `"mode"` key selects the class, whose fields (plus the [Common] ones) sit
 * next to it in the same JSON object.
 */
interface ShaperMode : FeatureConfiguration {
	val common: Common

	val type: Type

	/** Builds the world-specific shaper (noise seeded from the mixed seed). */
	fun createShaper(seed: Long): Shaper

	enum class Type(val id: String, private val codecSupplier: () -> MapCodec<out ShaperMode>) {
		FLATTEN("flatten", { FlattenMode.CODEC }),
		DEPRESSION("depression", { DepressionMode.CODEC }),
		DUNES("dunes", { DunesMode.CODEC }),
		TERRACES("terraces", { TerracesMode.CODEC }),
		CANYON("canyon", { CanyonMode.CODEC }),
		U_VALLEY("u_valley", { UValleyMode.CODEC }),
		ICE_SHEET("ice_sheet", { IceSheetMode.CODEC }),
		GLACIER("glacier", { GlacierMode.CODEC }),
		WETLAND("wetland", { WetlandMode.CODEC }),
		YARDANGS("yardangs", { YardangsMode.CODEC }),
		ROLLING("rolling", { RollingMode.CODEC }),
		SEA_CLIFF("sea_cliff", { SeaCliffMode.CODEC }),
		ISLANDS("islands", { IslandsMode.CODEC });

		fun codec(): MapCodec<out ShaperMode> = codecSupplier()

		companion object {
			private val BY_ID: Map<String, Type> = entries.associateBy { it.id }

			@JvmField
			val CODEC: Codec<Type> = Codec.STRING.comapFlatMap(::byId, Type::id)

			private fun byId(id: String): DataResult<Type> {
				val t = BY_ID[id]
				return if (t != null) DataResult.success(t)
				else DataResult.error { "Unknown terrain_shaper mode '$id', expected one of ${BY_ID.keys}" }
			}
		}
	}

	companion object {
		@JvmField
		val CODEC: Codec<ShaperMode> = Type.CODEC.dispatch("mode", { it.type }, { it.codec() })
	}
}
