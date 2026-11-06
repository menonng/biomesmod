package io.github.menonng.biomesmod.terrain.landform;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import java.util.LinkedHashMap;
import java.util.Map;

/** Registry of {@code "shape"} values for {@code biomesmod:landform}. */
public final class LandformShapes {
	private LandformShapes() {
	}

	private static final Map<String, MapCodec<? extends LandformShape>> CODECS = new LinkedHashMap<>();

	static {
		CODECS.put(PillarForestShape.NAME, PillarForestShape.CODEC);
		CODECS.put(ButteShape.NAME, ButteShape.CODEC);
		CODECS.put(MonolithShape.NAME, MonolithShape.CODEC);
		CODECS.put(ConeShape.NAME, ConeShape.CODEC);
		CODECS.put(CalderaShape.NAME, CalderaShape.CODEC);
		CODECS.put(SeaStackShape.NAME, SeaStackShape.CODEC);
		CODECS.put(ColumnFieldShape.NAME, ColumnFieldShape.CODEC);
		CODECS.put(HotSpringShape.NAME, HotSpringShape.CODEC);
		CODECS.put(CascadeShape.NAME, CascadeShape.CODEC);
	}

	private static final Codec<String> NAME = Codec.STRING.validate(s -> CODECS.containsKey(s)
			? DataResult.success(s)
			: DataResult.error(() -> "Unknown landform shape '" + s + "', expected one of " + CODECS.keySet()));

	static final MapCodec<LandformShape> MAP_CODEC = NAME.dispatchMap("shape", LandformShape::name, CODECS::get);

	public static Iterable<String> names() {
		return CODECS.keySet();
	}
}
