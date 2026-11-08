package io.github.menonng.biomesmod

import io.github.menonng.biomesmod.compat.Ids
import io.github.menonng.biomesmod.compat.LevelCompat
import io.github.menonng.biomesmod.debug.DebugRender
import io.github.menonng.biomesmod.site.SiteConfig
import io.github.menonng.biomesmod.site.SiteField
import io.github.menonng.biomesmod.terrain.field.FieldSampler
import io.github.menonng.biomesmod.terrain.landform.LandformConfig
import io.github.menonng.biomesmod.terrain.landform.LandformFeature
import io.github.menonng.biomesmod.terrain.util.WorldSampler
import io.github.menonng.biomesmod.worldgen.Placements
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.registries.Registries
import net.minecraft.server.MinecraftServer
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt
import kotlin.system.exitProcess

/**
 * End-to-end load check, enabled with -Dbiomesmod.selfcheck=true (see the selfCheck Loom run). For every mod biome, in
 * its own dimension: the registry entry exists, the dimension's biome source can produce it, and a /locate-style search
 * finds it in generated terrain. Exits the JVM with 0 or 1.
 */
internal object SelfCheck {
	private const val RADIUS = 12000

	/** Site biomes are located from the site layout itself, which is cheap, so the search can go much further. */
	private const val SITE_RADIUS = 60000

	/** Distance from the last interior point to the patch edge (blocks), for logs. */
	private var lastInteriorRadius = 0

	fun run(server: MinecraftServer) {
		if (System.getProperty("biomesmod.showcase", "").isNotEmpty()) {
			return // a dev showcase owns this run
		}
		val failures = ArrayList<String>()
		val biomes = server.registryAccess().lookupOrThrow(Registries.BIOME)

		val onlyBiomes = System.getProperty("biomesmod.selfcheck.only", "")
		for (entry in ModBiomes.ALL) {
			val name = "${Ids.name(entry.key)} [${Ids.name(entry.dimension)}]"
			if (onlyBiomes.isNotEmpty() && Ids.name(entry.key).replace("biomesmod:", "") !in onlyBiomes.split(",")) {
				continue
			}
			val level = server.getLevel(entry.dimension)
			if (level == null) {
				failures.add("$name: dimension is not loaded")
				continue
			}
			val holder = biomes.get(entry.key)
			if (holder.isEmpty) {
				failures.add("$name: not in biome registry")
				continue
			}
			if (!level.chunkSource.generator.biomeSource.possibleBiomes().contains(holder.get())) {
				failures.add("$name: not a possible biome of the dimension's biome source")
				continue
			}
			val id = Ids.name(entry.key).substringAfter(':')
			val siteSpec = Placements.get().sites.specs.firstOrNull { it.biome == Ids.name(entry.key) }
			if (siteSpec != null) {
				checkSite(level, holder.get(), entry, siteSpec, name, id, failures)
				continue
			}
			val parent = Placements.get().sites.specs.firstOrNull { sp -> sp.zones.any { it.biome == Ids.name(entry.key) } }
			if (parent != null) {
				checkZone(level, holder.get(), entry, parent, name, id, failures)
				continue
			}
			// End highlands start ~1000 blocks out; elsewhere search from the origin.
			val origin = if (entry.dimension == Level.END) BlockPos(1500, 64, 0) else BlockPos(0, 64, 0)
			val start = System.currentTimeMillis()
			val found = level.findClosestBiome3d({ it.`is`(entry.key) }, origin, RADIUS, 32, 64)
			if (found == null) {
				failures.add("$name: not found within $RADIUS blocks")
				continue
			}
			// Several search origins: render the largest patch found, not just the one nearest the origin.
			var inner = interior(level, found.first, entry)
			var bestR = lastInteriorRadius
			if (entry.dimension == Level.OVERWORLD) {
				for (o in arrayOf(intArrayOf(4000, 0), intArrayOf(-4000, 0), intArrayOf(0, 4000), intArrayOf(0, -4000))) {
					val f2 = level.findClosestBiome3d({ it.`is`(entry.key) }, BlockPos(o[0], 64, o[1]), 3000, 32, 64) ?: continue
					val in2 = interior(level, f2.first, entry)
					if (lastInteriorRadius > bestR) {
						bestR = lastInteriorRadius
						inner = in2
					}
				}
			}
			lastInteriorRadius = bestR
			BiomesMod.LOGGER.info("[selfcheck] {} OK - nearest at {}, interior {} r={} ({} ms); surface: {}", name, found.first.toShortString(),
				inner.toShortString(), lastInteriorRadius, System.currentTimeMillis() - start, surfaceSummary(level, inner, entry))
			// Landforms: probe the largest patch, and the nearest one too when it is a different patch.
			val placed = ArrayList<IntArray>()
			landformDiagnostics(level, holder.get(), inner, name, placed)
			val near = found.first
			if (near.distSqr(inner) > 768.0 * 768.0) {
				landformDiagnostics(level, holder.get(), near, "$name (nearest patch)", placed)
			}
			renderIfSelected(level, inner, id, placed)
		}

		val code = if (failures.isEmpty()) 0 else 1
		if (code == 0) {
			BiomesMod.LOGGER.info("[selfcheck] PASSED ({} biome(s))", ModBiomes.ALL.size)
		} else {
			failures.forEach { BiomesMod.LOGGER.error("[selfcheck] FAIL {}", it) }
		}
		// Exit from another thread so the server's shutdown hook can stop the server thread cleanly.
		Thread({ exitProcess(code) }, "biomesmod-selfcheck-exit").start()
	}

	private fun renderIfSelected(level: ServerLevel, inner: BlockPos, id: String, placed: List<IntArray>) {
		val only = System.getProperty("biomesmod.render.only", "")
		if (DebugRender.enabled() && (only.isEmpty() || id in only.split(","))) {
			DebugRender.render(level, inner, DebugRender.size(), id)
			DebugRender.iso(level, inner, min(DebugRender.size(), 192), id)
			if (placed.isNotEmpty()) {
				// A second view centred on one placed landform, which the patch-centre view often misses.
				val at = BlockPos(placed[0][0], 64, placed[0][1])
				DebugRender.render(level, at, DebugRender.size(), "${id}_landform")
				DebugRender.iso(level, at, min(DebugRender.size(), 112), "${id}_landform", 3)
				DebugRender.iso(level, at, min(DebugRender.size(), 112), "${id}_landform", 3, true)
			}
		}
	}

	/** A zone biome (a sub-biome of a multi-zone site): the parent's nearest site must contain it. */
	private fun checkZone(level: ServerLevel, biome: Holder<Biome>, entry: ModBiomes.Entry, parent: SiteConfig.Spec, name: String, id: String,
		failures: MutableList<String>) {
		val sampler = level.chunkSource.randomState().sampler()
		val field = SiteField.forSampler(sampler)
		val s = field?.sitesNear(0, 0, SITE_RADIUS, parent.biome)?.firstOrNull()
		if (s == null) {
			failures.add("$name: no ${parent.biome} site within $SITE_RADIUS blocks")
			return
		}
		val world = WorldSampler.of(level, level.chunkSource.generator)
		val source = level.chunkSource.generator.biomeSource
		val r = s.outer().toInt()
		var hits = 0
		var first: BlockPos? = null
		for (dz in -r..r step 16) {
			for (dx in -r..r step 16) {
				val x = s.x + dx
				val z = s.z + dz
				val y = max(world.baseFloorHeight(x, z), level.seaLevel) + 1
				if (source.getNoiseBiome(x shr 2, y shr 2, z shr 2, sampler).`is`(entry.key)) {
					hits++
					if (first == null) first = BlockPos(x, y, z)
				}
			}
		}
		val at = first
		if (at == null) {
			failures.add("$name: zone of ${parent.biome} but absent from its site at ${s.x},${s.z}")
			return
		}
		BiomesMod.LOGGER.info("[selfcheck] {} OK - zone of {} site at {}, {}: {} of the 16-block samples, first at {}; surface: {}", name, parent.biome,
			s.x, s.z, hits, at.toShortString(), surfaceSummary(level, at, entry))
		val placed = ArrayList<IntArray>()
		landformDiagnostics(level, biome, at, name, placed)
		renderIfSelected(level, at, id, placed)
	}

	/**
	 * A site-placed biome: the nearest site from the origin, its biome at the centre, and the noise terrain over the
	 * core (elevation against the target, flat area). Flat plateaus (relief <= 0.05) that target y >= 200 must hold at
	 * least 600,000 m^2 of flat land at y >= 200 (the Salar de Uyuni requirement).
	 */
	private fun checkSite(level: ServerLevel, biome: Holder<Biome>, entry: ModBiomes.Entry, spec: SiteConfig.Spec, name: String, id: String,
		failures: MutableList<String>) {
		val start = System.currentTimeMillis()
		val sampler = level.chunkSource.randomState().sampler()
		val field = SiteField.forSampler(sampler)
		if (field == null) {
			failures.add("$name: the overworld has no site field (density functions not wrapped?)")
			return
		}
		val sites = field.sitesNear(0, 0, SITE_RADIUS, spec.biome)
		if (sites.isEmpty()) {
			failures.add("$name: no site within $SITE_RADIUS blocks")
			return
		}
		val s = sites.first()
		val world = WorldSampler.of(level, level.chunkSource.generator)
		val source = level.chunkSource.generator.biomeSource
		val sea = level.seaLevel
		val centreFloor = world.baseFloorHeight(s.x, s.z) - 1
		val atCentre = source.getNoiseBiome(s.x shr 2, (max(centreFloor, sea) + 1) shr 2, s.z shr 2, sampler)
		val allowed = listOf(spec.biome) + spec.zones.map { it.biome }
		if (allowed.none { b -> atCentre.`is`(ResourceKey.create(Registries.BIOME, Ids.id(b.substringAfter(':')))) }) {
			failures.add("$name: site centre ${s.x},${s.z} has biome ${atCentre.unwrapKey().map { Ids.name(it) }.orElse("?")}")
		}
		// Noise terrain over the site, every 16 blocks: elevation inside the core, flat area, water share.
		val core = ArrayList<Int>()
		var coreCells = 0
		var flat = 0
		var flatHigh = 0
		var wet = 0
		val r = ceil(s.core * 1.35).toInt()
		for (dz in -r..r step 16) {
			for (dx in -r..r step 16) {
				val x = s.x + dx
				val z = s.z + dz
				val b = field.blend(x, z)
				if (b.a != s || b.wa < 0.999) {
					continue
				}
				coreCells++
				val h = world.baseFloorHeight(x, z) - 1
				core.add(h)
				if (h < sea - 1) {
					wet++
				}
				if (abs(h - spec.targetY) <= 3) {
					flat++
					if (h >= 200) {
						flatHigh++
					}
				}
			}
		}
		core.sort()
		val terrain = if (core.isEmpty()) "no core samples" else
			"core %d m^2, noise ground median %d (min %d, max %d), target %s y=%d, within 3 of target %d m^2 (%d m^2 at y>=200), under sea %d%%".format(
				coreCells * 256, core[core.size / 2], core.first(), core.last(), spec.terrain.name.lowercase(), spec.targetY, flat * 256,
				flatHigh * 256, (100.0 * wet / max(1, coreCells)).roundToLong())
		if (spec.terrain == SiteConfig.Terrain.PLATEAU && spec.relief <= 0.05 && spec.targetY >= 200 && flatHigh * 256 < 600_000) {
			failures.add("$name: plateau has only ${flatHigh * 256} m^2 of flat land at y>=200 (needs 600000)")
		}
		val inner = BlockPos(s.x, 64, s.z)
		lastInteriorRadius = s.core.toInt()
		BiomesMod.LOGGER.info("[selfcheck] {} OK - site {} of {} within {} blocks at {}, {} (core {} + ramp {}) ({} ms); {}; surface: {}", name, 1,
			sites.size, SITE_RADIUS, s.x, s.z, s.core.roundToLong(), s.ramp.roundToLong(), System.currentTimeMillis() - start, terrain,
			surfaceSummary(level, inner, entry))
		val placed = ArrayList<IntArray>()
		landformDiagnostics(level, biome, inner, name, placed)
		if (java.lang.Boolean.getBoolean("biomesmod.overview")) {
			DebugRender.overview(level, inner, 160, 14, id, spec.biome)
		}
		renderIfSelected(level, inner, id, placed)
	}

	/** Logs, for every landform feature of the biome, how many nearby grid cells place an instance and why the rest do not. */
	private fun landformDiagnostics(level: ServerLevel, biome: Holder<Biome>, at: BlockPos, name: String, placedOut: MutableList<IntArray>) {
		val generator = level.chunkSource.generator
		val sampler = WorldSampler.of(level, generator)
		val floor = sampler.baseFloorHeight(at.x, at.z) - 1
		val surfaceY = max(floor, sampler.seaLevel)
		BiomesMod.LOGGER.info("[selfcheck] {} probe at {}: noise floor {}, sampler biome@floor {}, @64 {}, level biome@floor {}", name, at.toShortString(),
			floor, biomeName(sampler.biomeAt(at.x, surfaceY, at.z)), biomeName(sampler.biomeAt(at.x, 64, at.z)),
			biomeName(level.getBiome(at.atY(surfaceY))) + ", field sampler (top of world) " +
				biomeName(FieldSampler.of(level, generator).biomeNode(at.x shr 3, at.z shr 3, 3)))
		for (step in biome.value().generationSettings.features()) {
			for (placed in step) {
				val cfg = placed.value().feature().value().config() as? LandformConfig ?: continue
				val before = placedOut.size
				val d = LandformFeature.diagnose(sampler, cfg, at.x, at.z, 768, placedOut)
				val where = StringBuilder()
				for (i in before until min(placedOut.size, before + 4)) {
					where.append(if (i == before) " at " else "; ").append(placedOut[i][0]).append(',').append(placedOut[i][1])
				}
				BiomesMod.LOGGER.info("[selfcheck] {} landform {}{}", name, d, where)
			}
		}
	}

	private fun biomeName(h: Holder<Biome>): String = h.unwrapKey().map { Ids.name(it) }.orElse("?")

	/**
	 * The point of the biome patch around [near] that lies farthest from its edge (sampled on a 16-block grid over
	 * 768x768 blocks with the generator's own biome source, so nothing is generated). The nearest point that
	 * findClosestBiome3d returns is on the patch edge, which shows mostly the neighbours in a render.
	 */
	private fun interior(level: ServerLevel, near: BlockPos, entry: ModBiomes.Entry): BlockPos {
		val step = 16
		val n = 49
		val source = level.chunkSource.generator.biomeSource
		val sampler = level.chunkSource.randomState().sampler()
		val inside = BooleanArray(n * n)
		val x0 = near.x - (n / 2) * step
		val z0 = near.z - (n / 2) * step
		val qy = near.y shr 2
		for (j in 0 until n) {
			for (i in 0 until n) {
				inside[i + j * n] = source.getNoiseBiome((x0 + i * step) shr 2, qy, (z0 + j * step) shr 2, sampler).`is`(entry.key)
			}
		}
		var best = -1
		var bestD = -1
		for (j in 0 until n) {
			for (i in 0 until n) {
				if (!inside[i + j * n]) {
					continue
				}
				var d = Int.MAX_VALUE
				var v = 0
				while (v < n && d > 0) {
					for (u in 0 until n) {
						if (!inside[u + v * n]) {
							d = min(d, (u - i) * (u - i) + (v - j) * (v - j))
						}
					}
					v++
				}
				if (d > bestD) {
					bestD = d
					best = i + j * n
				}
			}
		}
		lastInteriorRadius = if (best < 0) 0 else (sqrt(bestD.toDouble()) * step).roundToInt()
		return if (best < 0) near else BlockPos(x0 + (best % n) * step, near.y, z0 + (best / n) * step)
	}

	/**
	 * Most common floor blocks (solid block with air above) inside the biome, in a 32x32 area around [center].
	 * Diagnostic for checking surface rules and features.
	 */
	private fun surfaceSummary(level: ServerLevel, center: BlockPos, entry: ModBiomes.Entry): String {
		val counts = HashMap<String, Int>()
		val pos = BlockPos.MutableBlockPos()
		val minY = LevelCompat.minY(level)
		for (dx in -16 until 16) {
			for (dz in -16 until 16) {
				val x = center.x + dx
				val z = center.z + dz
				for (y in minY + 1 until minY + level.height - 1) {
					pos.set(x, y, z)
					val state = level.getBlockState(pos)
					if (state.isAir || !level.getBlockState(pos.above()).isAir || !level.getBiome(pos).`is`(entry.key)) {
						continue
					}
					counts.merge(state.block.name.string, 1, Int::plus)
				}
			}
		}
		val blocks = counts.entries.sortedByDescending { it.value }.take(4).joinToString(", ") { "${it.key} x${it.value}" }
		return blocks + terrainStats(level, center, entry)
	}

	/**
	 * Ground height stats of the biome's columns in a 64x64 area: median/min/max of the top solid block and the share of
	 * columns with water over the ground. Verifies terrain shaping numerically, e.g. that a polder floor really lies
	 * below sea level and stays dry.
	 */
	private fun terrainStats(level: ServerLevel, center: BlockPos, entry: ModBiomes.Entry): String {
		if (entry.dimension != Level.OVERWORLD) {
			return ""
		}
		// heightmaps of ungenerated chunks read as empty
		DebugRender.generate(level, (center.x - 32) shr 4, (center.z - 32) shr 4, (center.x + 31) shr 4, (center.z + 31) shr 4)
		val floors = ArrayList<Int>()
		var wet = 0
		val pos = BlockPos.MutableBlockPos()
		val minY = LevelCompat.minY(level)
		for (dx in -32 until 32 step 2) {
			for (dz in -32 until 32 step 2) {
				val x = center.x + dx
				val z = center.z + dz
				// Real ground: scan down from the surface past leaves, logs, plants and fluids.
				var y = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1
				var fluid = false
				while (y > minY) {
					pos.set(x, y, z)
					val st = level.getBlockState(pos)
					if (!st.fluidState.isEmpty) {
						fluid = true
					}
					if (st.isSolid && !st.`is`(BlockTags.LEAVES) && !st.`is`(BlockTags.LOGS) && st.fluidState.isEmpty) {
						break
					}
					y--
				}
				pos.set(x, y, z)
				if (!level.getBiome(pos).`is`(entry.key)) {
					continue
				}
				floors.add(y)
				if (fluid) {
					wet++
				}
			}
		}
		if (floors.isEmpty()) {
			return ""
		}
		floors.sort()
		return "; ground median %d (min %d, max %d, sea %d), wet %d%% of %d columns".format(floors[floors.size / 2], floors.first(), floors.last(),
			level.seaLevel, (100.0 * wet / floors.size).roundToLong(), floors.size)
	}
}
