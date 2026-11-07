package io.github.menonng.biomesmod.terrain.field

import io.github.menonng.biomesmod.BiomesMod
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Per-mode timing of terrain_shaper chunks (only chunks where the shaper actually did work are counted as "shaped";
 * skipped chunks cost a biome lookup). Logged by the showcase, or every 2000 shaped chunks when
 * `-Dbiomesmod.field.stats=true`.
 */
object FieldStats {
	private val PERIODIC = java.lang.Boolean.getBoolean("biomesmod.field.stats")

	private class Entry {
		val chunks = AtomicLong()
		val skipped = AtomicLong()
		val nanos = AtomicLong()
		val maxNanos = AtomicLong()
		val writes = AtomicLong()
	}

	private val STATS = EnumMap<ShaperMode.Type, Entry>(ShaperMode.Type::class.java).also { m ->
		ShaperMode.Type.entries.forEach { m[it] = Entry() }
	}

	internal fun record(type: ShaperMode.Type, nanos: Long, writes: Int, shaped: Boolean) {
		val e = STATS[type]!!
		if (!shaped) {
			e.skipped.incrementAndGet()
			return
		}
		val c = e.chunks.incrementAndGet()
		e.nanos.addAndGet(nanos)
		e.writes.addAndGet(writes.toLong())
		e.maxNanos.accumulateAndGet(nanos) { a, b -> max(a, b) }
		if (PERIODIC && c % 2000 == 0L) {
			BiomesMod.LOGGER.info("[terrain_shaper] {}", line(type, e))
		}
	}

	fun reset() {
		for (e in STATS.values) {
			e.chunks.set(0)
			e.skipped.set(0)
			e.nanos.set(0)
			e.maxNanos.set(0)
			e.writes.set(0)
		}
	}

	/** One line for a mode, or null when it never ran. */
	fun summary(type: ShaperMode.Type): String? {
		val e = STATS[type]!!
		return if (e.chunks.get() == 0L && e.skipped.get() == 0L) null else line(type, e)
	}

	private fun line(type: ShaperMode.Type, e: Entry): String {
		val c = max(1L, e.chunks.get())
		return "%s: %d shaped chunks, avg %.2f ms, max %.2f ms, %d block writes/chunk, %d skipped".format(
			type.id, e.chunks.get(), e.nanos.get() / 1e6 / c, e.maxNanos.get() / 1e6, e.writes.get() / c, e.skipped.get())
	}
}
