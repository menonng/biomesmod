package io.github.menonng.biomesmod.terrain.landform;

import io.github.menonng.biomesmod.terrain.ModFeatures;

/** Registers the grid-placed landform feature and the umbrella tree. */
public final class LandformFeatures {
	public static final LandformFeature LANDFORM = new LandformFeature();
	public static final UmbrellaTreeFeature UMBRELLA_TREE = new UmbrellaTreeFeature();

	private LandformFeatures() {
	}

	public static void register() {
		ModFeatures.register("landform", LANDFORM);
		ModFeatures.register("umbrella_tree", UMBRELLA_TREE);
	}
}
