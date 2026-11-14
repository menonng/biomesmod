package io.github.menonng.biomesmod.site

import io.github.menonng.biomesmod.compat.Ids
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.structure.Structure
import kotlin.math.max
import kotlin.math.min

/** The ground check behind `JigsawStructureMixin`: false where a village of this mod's biomes would not stand level. */
object LevelGround {
	private val RADII = intArrayOf(20, 40)
	private val DX = intArrayOf(1, 1, 0, -1, -1, -1, 0, 1)
	private val DZ = intArrayOf(0, 1, 1, 1, 0, -1, -1, -1)
	private const val MAX_RANGE = 6

	@JvmStatic
	fun allows(context: Structure.GenerationContext): Boolean {
		val x = context.chunkPos().getMiddleBlockX()
		val z = context.chunkPos().getMiddleBlockZ()
		val generator = context.chunkGenerator()
		val random = context.randomState()
		val y = generator.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), random)
		val biome = context.biomeSource().getNoiseBiome(x shr 2, y shr 2, z shr 2, random.sampler())
		if (!biome.unwrapKey().map { Ids.name(it).startsWith("biomesmod:") }.orElse(false)) {
			return true
		}
		var low = y
		var high = y
		for (r in RADII) for (k in 0 until 8) {
			val h = generator.getBaseHeight(x + r * DX[k], z + r * DZ[k], Heightmap.Types.WORLD_SURFACE_WG, context.heightAccessor(), random)
			low = min(low, h)
			high = max(high, h)
			if (high - low > MAX_RANGE) {
				return false
			}
		}
		return true
	}
}
