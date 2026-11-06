package io.github.menonng.biomesmod.test;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * 1.21.1's GameTestServer refuses to start with zero test functions, so the
 * dev-only selfCheck run needs at least one. The real checks live in SelfCheck.
 */
public final class BootGameTest implements FabricGameTest {
	@GameTest(template = EMPTY_STRUCTURE)
	public void boots(GameTestHelper helper) {
		helper.succeed();
	}
}
