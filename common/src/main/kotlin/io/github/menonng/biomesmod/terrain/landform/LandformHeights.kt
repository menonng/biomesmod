package io.github.menonng.biomesmod.terrain.landform

import io.github.menonng.biomesmod.compat.Ids
import io.github.menonng.biomesmod.terrain.util.WorldSampler
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.RandomState
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Landforms are built by features, after structures were planned on the noise terrain: a village laid out where a
 * volcano will stand ends up inside it. While a village is being planned (`JigsawStructureMixin`), the generator's
 * height queries therefore answer with the landforms already standing (`ChunkGeneratorHeightMixin`), so the level
 * ground check sees the cone and the houses are set on its flank, not under it.
 */
object LandformHeights {
	class State {
		@JvmField var depth = 0
		@JvmField var seed = 0L
		@JvmField var busy = false
	}

	private val STATE: ThreadLocal<State> = ThreadLocal.withInitial { State() }
	private val CONFIGS = ConcurrentHashMap<Biome, List<LandformConfig>>()

	/** Runs [body] with landform-aware height queries on this thread. */
	@JvmStatic
	fun <T> planning(seed: Long, body: () -> T): T {
		val st = STATE.get()
		st.depth++
		st.seed = seed
		try {
			return body()
		} finally {
			st.depth--
		}
	}

	/** The height the generator reported at (x, z), raised or cut to the landform that will stand there. */
	@JvmStatic
	fun adjust(generator: ChunkGenerator, random: RandomState, heights: LevelHeightAccessor, x: Int, z: Int, base: Int): Int {
		val st = STATE.get()
		if (st.depth == 0 || st.busy) {
			return base
		}
		st.busy = true // the landforms themselves ask for the bare noise terrain
		try {
			val sampler = WorldSampler.detached(heights, generator, random, st.seed)
			val biome = sampler.biomeAt(x, max(base, sampler.seaLevel), z)
			if (!biome.unwrapKey().map { Ids.name(it).startsWith("biomesmod:") }.orElse(false)) {
				return base
			}
			val configs = CONFIGS.computeIfAbsent(biome.value()) { b ->
				b.generationSettings.features().flatMap { step -> step.mapNotNull { it.value().feature().value().config() as? LandformConfig } }
			}
			var top = base
			for (cfg in configs) {
				for (lf in LandformFeature.instancesOverlapping(sampler, cfg, x, z, x, z)) {
					val c = Col()
					lf.column(x, z, c)
					if (c.top == Col.NONE) {
						continue
					}
					val t = (if (c.water != Col.NONE) max(c.top, c.water) else c.top) + 1
					if (c.carve || t > top) {
						top = t
					}
				}
			}
			return top
		} finally {
			st.busy = false
		}
	}
}
