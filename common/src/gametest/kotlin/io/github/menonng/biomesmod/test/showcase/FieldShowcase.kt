package io.github.menonng.biomesmod.test.showcase

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import io.github.menonng.biomesmod.BiomesMod
import io.github.menonng.biomesmod.compat.LevelCompat
import io.github.menonng.biomesmod.debug.DebugRender
import io.github.menonng.biomesmod.terrain.field.FieldStats
import io.github.menonng.biomesmod.terrain.field.ShaperMode
import io.github.menonng.biomesmod.terrain.field.TerrainFeatures
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.resources.RegistryOps
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import java.util.Optional

/**
 * Dev showcase for biomesmod:terrain_shaper: every mode, decoded from JSON through the real codec, applied over a
 * 256x256 area on suitable vanilla terrain, then rendered (run/selfcheck/renders/field_*.png).
 * `./gradlew :26.2:runSelfCheck -Pshowcase=field -Prender=true`; add -Ponly=<name> to run one.
 */
object FieldShowcase {
	private class Case(val name: String, val near: ResourceKey<Biome>, val json: String)

	private val CASES = arrayOf(
		Case("flatten", Biomes.DESERT, """{"mode":"flatten","biomes":[],"crust":0.7,"water_coverage":0.3,"relief":0.4}"""),
		Case("depression", Biomes.PLAINS, """{"mode":"depression","biomes":["minecraft:plains"],"edge_blend":10,"depth":7,"ditch_spacing":28}"""),
		Case("dunes_lagoons", Biomes.BEACH, """{"mode":"dunes","biomes":[],"height":9,"wavelength":36,"lagoon_depth":3,""" +
			""""palette":{"top":[{"block":{"Name":"minecraft:white_concrete_powder"},"depth":2},{"block":{"Name":"minecraft:sand"},"depth":3}]}}"""),
		Case("dunes_star", Biomes.DESERT, """{"mode":"dunes","biomes":[],"style":"star","height":38,"wavelength":95,"pan_coverage":0.18,""" +
			""""palette":{"top":[{"block":{"Name":"minecraft:red_sand"},"depth":4}]}}"""),
		Case("terraces_pamukkale", Biomes.MEADOW, """{"mode":"terraces","biomes":[],"step":2,"pool_depth":1,"tilt":30,"lobe":1.4}"""),
		Case("terraces_plitvice", Biomes.FOREST, """{"mode":"terraces","biomes":[],"step":7,"pool_depth":5,"rim_width":2.5,"falls":0.5,"tilt":45,"pool_coverage":0.4,""" +
			""""palette":{"top":[{"block":{"Name":"minecraft:moss_block"},"depth":1},{"block":{"Name":"minecraft:calcite"},"depth":2}],""" +
			""""underwater":[{"block":{"Name":"minecraft:calcite"},"depth":2}]}}"""),
		Case("canyon", Biomes.BADLANDS, """{"mode":"canyon","biomes":[],"spacing":200,"width":120,"depth":60,"side_spacing":110}"""),
		Case("u_valley", Biomes.MEADOW, """{"mode":"u_valley","biomes":[],"spacing":220,"width":100,"floor_width":40,"shoulder":55,"falls":4}"""),
		Case("fjord", Biomes.STONY_SHORE, """{"mode":"u_valley","biomes":[],"spacing":200,"width":90,"floor_width":34,"shoulder":60,"floor_depth":14,""" +
			""""flood_to_sea":true,"falls":3}"""),
		Case("ice_sheet", Biomes.SNOWY_PLAINS, """{"mode":"ice_sheet","biomes":[],"thickness":16,"dome":10}"""),
		Case("ice_shelf", Biomes.SNOWY_BEACH, """{"mode":"ice_sheet","biomes":[],"shelf":true,"freeboard":12,"thickness":10,"dome":6}"""),
		Case("glacier", Biomes.JAGGED_PEAKS, """{"mode":"glacier","biomes":[],"drop":4,"snowline":170}"""),
		Case("wetland", Biomes.SWAMP, """{"mode":"wetland","biomes":[],"dry_coverage":0.4,"channels":0.4}"""),
		Case("yardangs", Biomes.DESERT, """{"mode":"yardangs","biomes":[],"height":20,"spacing":30,"length":150}"""),
		Case("rolling_mesa", Biomes.SAVANNA, """{"mode":"rolling","biomes":[],"smooth":0.85,"hills":4,"mesa_step":12}"""),
	)

	fun run(server: MinecraftServer) {
		val level = server.getLevel(Level.OVERWORLD)!!
		val only = System.getProperty("biomesmod.showcase.only", "")
		val ops: RegistryOps<JsonElement> = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess())
		for ((index, c) in CASES.withIndex()) {
			val k = index + 1
			if (only.isNotEmpty() && c.name !in only.split(",")) {
				continue
			}
			val mode = ShaperMode.CODEC.parse(ops, JsonParser.parseString(c.json)).result().orElseThrow()
			val center = find(level, { it.`is`(c.near) }, BlockPos(k * 3000, 64, -k * 1700))
			if (center == null) {
				BiomesMod.LOGGER.warn("[showcase] {}: no {} found, skipped", c.name, c.near)
				continue
			}
			apply(level, mode, center, 256)
			DebugRender.render(level, center, 256, "field_${c.name}")
			BiomesMod.LOGGER.info("[showcase] field_{} at {} | {}", c.name, center.toShortString(), FieldStats.summary(mode.type))
		}
	}

	fun find(level: ServerLevel, biome: (Holder<Biome>) -> Boolean, from: BlockPos): BlockPos? {
		val p = level.findClosestBiome3d({ biome(it) }, from, 6400, 32, 64) ?: return null
		return BlockPos(p.first.x, 64, p.first.z)
	}

	/** Generates the area, then runs the feature once per chunk at the chunk origin, like worldgen does. */
	private fun apply(level: ServerLevel, mode: ShaperMode, center: BlockPos, size: Int) {
		val half = size / 2
		val cx0 = (center.x - half) shr 4
		val cz0 = (center.z - half) shr 4
		val cx1 = (center.x + half - 1) shr 4
		val cz1 = (center.z + half - 1) shr 4
		for (cz in cz0 - 1..cz1 + 1) {
			for (cx in cx0 - 1..cx1 + 1) {
				level.getChunk(cx, cz)
			}
		}
		val random = RandomSource.create(42)
		val generator = level.chunkSource.generator
		for (cz in cz0..cz1) {
			for (cx in cx0..cx1) {
				val origin = BlockPos(cx shl 4, LevelCompat.minY(level), cz shl 4)
				TerrainFeatures.TERRAIN_SHAPER.place(FeaturePlaceContext(Optional.empty(), level, generator, random, origin, mode))
			}
		}
	}
}
