package io.github.menonng.biomesmod.terrain.landform

import io.github.menonng.biomesmod.compat.Ids
import io.github.menonng.biomesmod.terrain.field.ChunkFrame
import io.github.menonng.biomesmod.terrain.field.FieldSampler
import io.github.menonng.biomesmod.terrain.field.ShaperMode
import io.github.menonng.biomesmod.terrain.field.TerrainShaperFeature
import io.github.menonng.biomesmod.terrain.util.WorldSampler
import net.minecraft.world.level.LevelHeightAccessor
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.chunk.ChunkGenerator
import net.minecraft.world.level.levelgen.RandomState
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Terrain shapers and landforms are built by features, after structures were planned on the noise terrain: a village
 * laid out where a volcano will stand ends up inside it, one planned across a future canyon hangs in the air. While a village is being planned (`JigsawStructureMixin`), the generator's
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
	private val BUILDERS = ConcurrentHashMap<Biome, List<Any>>()

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

	@JvmStatic
	fun active(): Boolean = STATE.get().let { it.depth > 0 && !it.busy }

	/**
	 * The height the generator reported at (x, z), with the ground that features will build there: terrain shapers and
	 * landforms, in the order they run. [base] is the generator's answer, [floor] its answer for the ocean floor (the
	 * first free block above the ground), [wantFloor] whether the ocean floor was asked for.
	 */
	@JvmStatic
	fun adjust(generator: ChunkGenerator, random: RandomState, heights: LevelHeightAccessor, x: Int, z: Int, base: Int, floor: Int, wantFloor: Boolean): Int {
		val st = STATE.get()
		if (st.depth == 0 || st.busy) {
			return base
		}
		st.busy = true // the shapers and landforms themselves ask for the bare noise terrain
		try {
			val sampler = WorldSampler.detached(heights, generator, random, st.seed)
			val biome = sampler.biomeAt(x, max(base, sampler.seaLevel), z)
			if (!biome.unwrapKey().map { Ids.name(it).startsWith("biomesmod:") }.orElse(false)) {
				return base
			}
			val builders = BUILDERS.computeIfAbsent(biome.value()) { b ->
				b.generationSettings.features().flatMap { step ->
					step.mapNotNull { placed -> placed.value().feature().value().config().takeIf { it is LandformConfig || it is ShaperMode } }
				}
			}
			var top = floor - 1
			var water = if (base > floor) base else Col.NONE
			var changed = false
			for (cfg in builders) {
				if (cfg is ShaperMode) {
					val fs = FieldSampler.of(heights, generator, random, st.seed)
					val t = ChunkFrame.plan(heights, generator, fs, cfg.common, TerrainShaperFeature.shaper(cfg, st.seed), x, z, top)
					val w = ChunkFrame.planWater()
					if (t != top || w != water) {
						changed = true
						top = t
						water = w
					}
				} else if (cfg is LandformConfig) {
					for (lf in LandformFeature.instancesOverlapping(sampler, cfg, x, z, x, z)) {
						val c = Col()
						lf.column(x, z, c)
						if (c.top == Col.NONE) {
							continue
						}
						if (c.carve || c.top > top) {
							top = c.top
							changed = true
						}
						if (c.water != Col.NONE && c.water > top) {
							water = max(water, c.water + 1)
							changed = true
						}
					}
				}
			}
			if (!changed) {
				return base
			}
			return if (wantFloor || water == Col.NONE) top + 1 else max(top + 1, water)
		} finally {
			st.busy = false
		}
	}
}
