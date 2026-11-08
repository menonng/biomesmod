package io.github.menonng.biomesmod

import com.google.gson.JsonParser
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.biome.Biome
import java.util.Collections
import java.util.IdentityHashMap

/** The biomes whose water never freezes (resource biomesmod/no_freeze.json), resolved to Biome instances per server. */
object NoFreeze {
	@Volatile
	private var set: Set<Biome> = emptySet()

	fun contains(biome: Biome): Boolean = biome in set

	fun load(server: MinecraftServer) {
		val ids = NoFreeze::class.java.getResourceAsStream("/biomesmod/no_freeze.json")?.use { s ->
			JsonParser.parseString(s.reader().readText()).asJsonArray.map { it.asString }.toSet()
		} ?: emptySet()
		val out = Collections.newSetFromMap(IdentityHashMap<Biome, Boolean>())
		server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME).listElements().forEach { h ->
			if (io.github.menonng.biomesmod.compat.Ids.name(h.key()) in ids) out.add(h.value())
		}
		set = out
		BiomesMod.LOGGER.info("[biomesmod] {} biome(s) keep their water unfrozen", out.size)
	}
}
