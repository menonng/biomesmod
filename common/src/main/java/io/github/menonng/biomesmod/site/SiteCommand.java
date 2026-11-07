package io.github.menonng.biomesmod.site;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.menonng.biomesmod.worldgen.Placements;
import java.util.List;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /biomesmod sites [biome]}: the nearest sites around you (all biomes, or one), with their centre, size and
 * the elevation they build. Sites are large and therefore rarer than vanilla biome patches, so this is the quick
 * way to find one; it only reads the seed-determined layout and generates nothing.
 */
public final class SiteCommand {
	private static final int SEARCH = 40_000;

	private SiteCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
				Commands.literal("biomesmod").then(Commands.literal("sites")
						.executes(c -> list(c, null))
						.then(Commands.argument("biome", StringArgumentType.greedyString())
								.suggests((c, b) -> SharedSuggestionProvider.suggest(
										Placements.get().sites().specs().stream().map(s -> s.biome().replace("biomesmod:", "")), b))
								.executes(c -> list(c, StringArgumentType.getString(c, "biome")))))));
	}

	private static int list(CommandContext<CommandSourceStack> c, String biome) {
		CommandSourceStack src = c.getSource();
		ServerLevel level = src.getLevel();
		SiteField field = SiteField.forSampler(level.getChunkSource().randomState().sampler());
		if (field == null) {
			src.sendFailure(Component.literal("No biomesmod sites in this dimension."));
			return 0;
		}
		String id = biome == null ? null : (biome.contains(":") ? biome : "biomesmod:" + biome).trim();
		Vec3 p = src.getPosition();
		List<SiteField.Site> sites = field.sitesNear((int) p.x, (int) p.z, id == null ? 6000 : SEARCH, id);
		if (sites.isEmpty()) {
			src.sendFailure(Component.literal(id == null ? "No site within 6000 blocks." : "No " + id + " site within " + SEARCH + " blocks."));
			return 0;
		}
		int shown = Math.min(sites.size(), id == null ? 12 : 5);
		for (int i = 0; i < shown; i++) {
			SiteField.Site s = sites.get(i);
			int dist = (int) Math.hypot(s.x() - p.x, s.z() - p.z);
			String line = String.format("%s  centre %d, %d  (%d blocks away)  radius %d+%d, %s y=%d",
					s.spec().biome().replace("biomesmod:", ""), s.x(), s.z(), dist, Math.round(s.core()), Math.round(s.ramp()),
					s.spec().terrain().name().toLowerCase(), s.spec().targetY());
			src.sendSuccess(() -> Component.literal(line), false);
		}
		return shown;
	}
}
