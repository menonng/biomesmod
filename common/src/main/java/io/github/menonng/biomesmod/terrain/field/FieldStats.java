package io.github.menonng.biomesmod.terrain.field;

import io.github.menonng.biomesmod.BiomesMod;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-mode timing of terrain_shaper chunks (only chunks where the shaper actually did work are counted as
 * "shaped"; skipped chunks cost a biome lookup). Logged by the showcase, or every 2000 shaped chunks when
 * {@code -Dbiomesmod.field.stats=true}.
 */
public final class FieldStats {
	private static final boolean PERIODIC = Boolean.getBoolean("biomesmod.field.stats");
	private static final Map<ShaperMode.Type, Entry> STATS = new EnumMap<>(ShaperMode.Type.class);

	static {
		for (ShaperMode.Type t : ShaperMode.Type.values()) {
			STATS.put(t, new Entry());
		}
	}

	private static final class Entry {
		final AtomicLong chunks = new AtomicLong();
		final AtomicLong skipped = new AtomicLong();
		final AtomicLong nanos = new AtomicLong();
		final AtomicLong maxNanos = new AtomicLong();
		final AtomicLong writes = new AtomicLong();
	}

	private FieldStats() {
	}

	static void record(ShaperMode.Type type, long nanos, int writes, boolean shaped) {
		Entry e = STATS.get(type);
		if (!shaped) {
			e.skipped.incrementAndGet();
			return;
		}
		long c = e.chunks.incrementAndGet();
		e.nanos.addAndGet(nanos);
		e.writes.addAndGet(writes);
		e.maxNanos.accumulateAndGet(nanos, Math::max);
		if (PERIODIC && c % 2000 == 0) {
			BiomesMod.LOGGER.info("[terrain_shaper] {}", line(type, e));
		}
	}

	public static void reset() {
		for (Entry e : STATS.values()) {
			e.chunks.set(0);
			e.skipped.set(0);
			e.nanos.set(0);
			e.maxNanos.set(0);
			e.writes.set(0);
		}
	}

	/** One line for a mode, or null when it never ran. */
	public static String summary(ShaperMode.Type type) {
		Entry e = STATS.get(type);
		return e.chunks.get() == 0 && e.skipped.get() == 0 ? null : line(type, e);
	}

	private static String line(ShaperMode.Type type, Entry e) {
		long c = Math.max(1, e.chunks.get());
		return String.format("%s: %d shaped chunks, avg %.2f ms, max %.2f ms, %d block writes/chunk, %d skipped",
				type.id(), e.chunks.get(), e.nanos.get() / 1e6 / c, e.maxNanos.get() / 1e6, e.writes.get() / c, e.skipped.get());
	}
}
