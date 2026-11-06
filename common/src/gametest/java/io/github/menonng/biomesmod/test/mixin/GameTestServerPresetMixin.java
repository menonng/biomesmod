package io.github.menonng.biomesmod.test.mixin;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Dev-only. The vanilla test server always builds a superflat world, whose overworld can only
 * produce plains. Swap the preset to the normal one so Overworld/Nether biome placement can be tested.
 * The read of WorldPresets.FLAT sits in a lambda inside GameTestServer.create, hence method = "*".
 */
@Mixin(GameTestServer.class)
abstract class GameTestServerPresetMixin {
	@Redirect(
			method = "*",
			at = @At(value = "FIELD", opcode = Opcodes.GETSTATIC,
					target = "Lnet/minecraft/world/level/levelgen/presets/WorldPresets;FLAT:Lnet/minecraft/resources/ResourceKey;"),
			require = 1)
	private static ResourceKey<WorldPreset> biomesmod$useNormalPreset() {
		return WorldPresets.NORMAL;
	}
}
