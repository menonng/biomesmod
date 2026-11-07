package io.github.menonng.biomesmod.site

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.menonng.biomesmod.worldgen.Placements
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * `/biomesmod sites [biome]`: the nearest sites around you (all biomes, or one), with their centre, size and the
 * elevation they build. Sites are large and therefore rarer than vanilla biome patches, so this is the quick way to
 * find one; it only reads the seed-determined layout and generates nothing.
 */
object SiteCommand {
	private const val SEARCH = 40_000

	fun register() {
		CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
			dispatcher.register(
				Commands.literal("biomesmod").then(Commands.literal("sites")
					.executes { list(it, null) }
					.then(Commands.argument("biome", StringArgumentType.greedyString())
						.suggests { _, builder ->
							SharedSuggestionProvider.suggest(Placements.get().sites.specs.map { it.biome.replace("biomesmod:", "") }, builder)
						}
						.executes { list(it, StringArgumentType.getString(it, "biome")) })))
		}
	}

	private fun list(c: CommandContext<CommandSourceStack>, biome: String?): Int {
		val src = c.source
		val level = src.level
		val field = SiteField.forSampler(level.chunkSource.randomState().sampler())
		if (field == null) {
			src.sendFailure(Component.literal("No biomesmod sites in this dimension."))
			return 0
		}
		val id = biome?.let { (if (it.contains(":")) it else "biomesmod:$it").trim() }
		val p = src.position
		val sites = field.sitesNear(p.x.toInt(), p.z.toInt(), if (id == null) 6000 else SEARCH, id)
		if (sites.isEmpty()) {
			src.sendFailure(Component.literal(if (id == null) "No site within 6000 blocks." else "No $id site within $SEARCH blocks."))
			return 0
		}
		val shown = min(sites.size, if (id == null) 12 else 5)
		for (s in sites.subList(0, shown)) {
			val dist = hypot(s.x - p.x, s.z - p.z).toInt()
			val line = "%s  centre %d, %d  (%d blocks away)  radius %d+%d, %s y=%d".format(
				s.spec.biome.replace("biomesmod:", ""), s.x, s.z, dist, s.core.roundToLong(), s.ramp.roundToLong(),
				s.spec.terrain.name.lowercase(), s.spec.targetY)
			src.sendSuccess({ Component.literal(line) }, false)
		}
		return shown
	}
}
