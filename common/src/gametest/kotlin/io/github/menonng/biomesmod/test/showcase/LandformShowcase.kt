package io.github.menonng.biomesmod.test.showcase

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import io.github.menonng.biomesmod.BiomesMod
import io.github.menonng.biomesmod.compat.LevelCompat
import io.github.menonng.biomesmod.debug.DebugRender
import io.github.menonng.biomesmod.terrain.landform.LandformConfig
import io.github.menonng.biomesmod.terrain.landform.LandformFeatures
import net.minecraft.core.BlockPos
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
import kotlin.math.max

/**
 * Dev showcase for biomesmod:landform: every shape decoded from JSON and applied chunk by chunk over a 256x256 area of
 * suitable terrain, then rendered (run/selfcheck/renders/landform_*.png). Use -Ponly=a,b to pick cases.
 */
object LandformShowcase {
	private class Case(val name: String, val near: ResourceKey<Biome>, val json: String)

	private const val G = """"spacing":200,"chance":1,"jitter":0.2,"biomes":[]"""

	private val CASES = arrayOf(
		Case("pillar_forest", Biomes.FOREST, """{"shape":"pillar_forest",$G,"cluster_radius":[36,48]}"""),
		Case("karst_towers", Biomes.WARM_OCEAN, """{"shape":"pillar_forest",$G,"rise_from":"sea_level","dome":1.0,"notch":2,""" +
			""""base_y_range":[-30,2],"max_relief":60,"pillar_radius":[5,10],"pillar_spacing":20,"height":[25,50]}"""),
		Case("butte", Biomes.DESERT, """{"shape":"butte",$G,"thumbs":0.8}"""),
		Case("monolith", Biomes.PLAINS, """{"shape":"monolith",$G}"""),
		Case("spires", Biomes.STONY_PEAKS, """{"shape":"monolith",$G,"count":3,"taper":0.85,"radius":[9,13],"height":[60,85],""" +
			""""columns":0,"flute":0.0,"max_relief":60}"""),
		Case("cone_sea", Biomes.OCEAN, """{"shape":"cone",$G,"rise_from":"sea_level","snowline":150,"crater":"dry"}"""),
		Case("caldera_sea", Biomes.OCEAN, """{"shape":"caldera",$G,"floor":"sea","rise_from":"sea_level"}"""),
		Case("caldera_lake", Biomes.PLAINS, """{"shape":"caldera",$G,"floor":"lake","cone":1}"""),
		Case("caldera_sand", Biomes.SAVANNA, """{"shape":"caldera",$G,"floor":"sand","cone":1,"floor_block":{"Name":"minecraft:gravel"}}"""),
		Case("sea_stack", Biomes.STONY_SHORE, """{"shape":"sea_stack","spacing":64,"chance":1,"biomes":[]}"""),
		Case("column_field", Biomes.BEACH, """{"shape":"column_field","spacing":96,"chance":1,"biomes":[]}"""),
		Case("hot_spring", Biomes.PLAINS, """{"shape":"hot_spring",$G}"""),
		Case("acid_pools", Biomes.DESERT, """{"shape":"hot_spring",$G,"pools":14,"pool_radius":[3,7],"band_width":1.2,"spread":[20,34],""" +
			""""depth":2,"chimneys":0.8,"bands":[{"Name":"minecraft:lime_terracotta"},{"Name":"minecraft:yellow_concrete_powder"},""" +
			"""{"Name":"minecraft:yellow_terracotta"},{"Name":"minecraft:white_terracotta"}]}"""),
		Case("cascade", Biomes.JUNGLE, """{"shape":"cascade",$G,"max_relief":40}"""),
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
			val config = LandformConfig.CODEC.parse(ops, JsonParser.parseString(c.json)).getOrThrow()
			val center = FieldShowcase.find(level, { it.`is`(c.near) }, BlockPos(-k * 2600, 64, k * 2100))
			if (center == null) {
				BiomesMod.LOGGER.warn("[showcase] {}: no {} found, skipped", c.name, c.near)
				continue
			}
			val t0 = System.nanoTime()
			val chunks = apply(level, config, center, 256)
			val ms = (System.nanoTime() - t0) / 1e6 / max(1, chunks)
			DebugRender.render(level, center, 256, "landform_${c.name}")
			BiomesMod.LOGGER.info("[showcase] landform_{} at {} | {} chunks, {} ms/chunk", c.name, center.toShortString(), chunks, "%.2f".format(ms))
		}
	}

	private fun apply(level: ServerLevel, config: LandformConfig, center: BlockPos, size: Int): Int {
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
		val random = RandomSource.create(7)
		val generator = level.chunkSource.generator
		var n = 0
		for (cz in cz0..cz1) {
			for (cx in cx0..cx1) {
				val origin = BlockPos(cx shl 4, LevelCompat.minY(level), cz shl 4)
				LandformFeatures.LANDFORM.place(FeaturePlaceContext(Optional.empty(), level, generator, random, origin, config))
				n++
			}
		}
		return n
	}
}
