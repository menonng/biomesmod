package io.github.menonng.biomesmod.terrain.field

import com.mojang.serialization.Codec
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import java.util.IdentityHashMap

/**
 * `biomesmod:terrain_shaper`: column-based terrain reshaping, once per chunk (placement `[]`, step `raw_generation`).
 * Writes only inside the chunk of the origin; every cross-chunk decision is a pure function of the world seed and
 * coordinates. See docs/engine-terrain.md.
 */
class TerrainShaperFeature(codec: Codec<ShaperMode>) : Feature<ShaperMode>(codec) {
	override fun place(context: FeaturePlaceContext<ShaperMode>): Boolean {
		val start = System.nanoTime()
		val mode = context.config()
		val level = context.level()
		val generator = context.chunkGenerator()
		val x0 = context.origin().x and 15.inv()
		val z0 = context.origin().z and 15.inv()
		val sampler = FieldSampler.of(level, generator)
		val shaper = shaper(mode, sampler.seed)
		val frame = ChunkFrame.get()
		var shaped = false
		try {
			frame.begin(level, generator, sampler, mode.common, shaper, x0, z0)
			if (frame.computeEdge(shaper.edgeReach())) {
				frame.readColumns()
				shaper.prepare(frame)
				frame.computeTargets()
				frame.resolve()
				frame.write()
				shaped = true
			}
			FieldStats.record(mode.type, System.nanoTime() - start, frame.writes, shaped)
		} finally {
			frame.end()
		}
		return shaped
	}

	companion object {
		private val SHAPERS = IdentityHashMap<ShaperMode, Shaper>()
		private val SHAPER_SEEDS = IdentityHashMap<ShaperMode, Long>()
		private val GOLDEN = 0x9E3779B97F4A7C15uL.toLong()

		internal fun shaper(mode: ShaperMode, worldSeed: Long): Shaper {
			synchronized(SHAPERS) {
				var s = SHAPERS[mode]
				val seed = SHAPER_SEEDS[mode]
				if (s == null || seed == null || seed != worldSeed) {
					val mixed = Noise.mix(worldSeed xor Noise.mix(mode.common.seed * GOLDEN + mode.type.ordinal * 0x632BE59BD9B4E019L + 0x1F))
					s = mode.createShaper(mixed)
					SHAPERS[mode] = s
					SHAPER_SEEDS[mode] = worldSeed
				}
				return s
			}
		}
	}
}
