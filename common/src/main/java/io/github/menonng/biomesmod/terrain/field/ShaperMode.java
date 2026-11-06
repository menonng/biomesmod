package io.github.menonng.biomesmod.terrain.field;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import io.github.menonng.biomesmod.terrain.field.mode.CanyonMode;
import io.github.menonng.biomesmod.terrain.field.mode.DepressionMode;
import io.github.menonng.biomesmod.terrain.field.mode.DunesMode;
import io.github.menonng.biomesmod.terrain.field.mode.FlattenMode;
import io.github.menonng.biomesmod.terrain.field.mode.GlacierMode;
import io.github.menonng.biomesmod.terrain.field.mode.IslandsMode;
import io.github.menonng.biomesmod.terrain.field.mode.IceSheetMode;
import io.github.menonng.biomesmod.terrain.field.mode.RollingMode;
import io.github.menonng.biomesmod.terrain.field.mode.TerracesMode;
import io.github.menonng.biomesmod.terrain.field.mode.UValleyMode;
import io.github.menonng.biomesmod.terrain.field.mode.WetlandMode;
import io.github.menonng.biomesmod.terrain.field.mode.YardangsMode;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

/**
 * Config of {@code biomesmod:terrain_shaper}: a {@code "mode"} key selects the record, whose fields (plus the
 * {@link Common} ones) sit next to it in the same JSON object.
 */
public interface ShaperMode extends FeatureConfiguration {
	Codec<ShaperMode> CODEC = Type.CODEC.dispatch("mode", ShaperMode::type, Type::codec);

	Common common();

	Type type();

	/** Builds the world-specific shaper (noise seeded from the mixed seed). */
	Shaper createShaper(long seed);

	enum Type {
		FLATTEN("flatten", () -> FlattenMode.CODEC),
		DEPRESSION("depression", () -> DepressionMode.CODEC),
		DUNES("dunes", () -> DunesMode.CODEC),
		TERRACES("terraces", () -> TerracesMode.CODEC),
		CANYON("canyon", () -> CanyonMode.CODEC),
		U_VALLEY("u_valley", () -> UValleyMode.CODEC),
		ICE_SHEET("ice_sheet", () -> IceSheetMode.CODEC),
		GLACIER("glacier", () -> GlacierMode.CODEC),
		WETLAND("wetland", () -> WetlandMode.CODEC),
		YARDANGS("yardangs", () -> YardangsMode.CODEC),
		ROLLING("rolling", () -> RollingMode.CODEC),
		ISLANDS("islands", () -> IslandsMode.CODEC);

		private static final Map<String, Type> BY_ID = Arrays.stream(values()).collect(Collectors.toMap(Type::id, Function.identity()));
		public static final Codec<Type> CODEC = Codec.STRING.comapFlatMap(Type::byId, Type::id);

		private final String id;
		private final Supplier<MapCodec<? extends ShaperMode>> codec;

		Type(String id, Supplier<MapCodec<? extends ShaperMode>> codec) {
			this.id = id;
			this.codec = codec;
		}

		public String id() {
			return id;
		}

		public MapCodec<? extends ShaperMode> codec() {
			return codec.get();
		}

		private static DataResult<Type> byId(String id) {
			Type t = BY_ID.get(id);
			return t != null ? DataResult.success(t)
					: DataResult.error(() -> "Unknown terrain_shaper mode '" + id + "', expected one of " + BY_ID.keySet());
		}
	}
}
