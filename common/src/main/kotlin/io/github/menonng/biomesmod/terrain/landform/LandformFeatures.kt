package io.github.menonng.biomesmod.terrain.landform

import io.github.menonng.biomesmod.terrain.ModFeatures

/** Registers the grid-placed landform feature and the custom trees. */
object LandformFeatures {
	@JvmField
	val LANDFORM = LandformFeature()

	@JvmField
	val UMBRELLA_TREE = UmbrellaTreeFeature()

	@JvmField
	val BOTTLE_TREE = BottleTreeFeature()

	@JvmField
	val FLOWER_STRIPS = FlowerStripsFeature()

	@JvmField
	val WINDMILL = WindmillFeature()

	@JvmField
	val GIANT_TREE = GiantTreeFeature()

	@JvmField
	val ROCK_SWAP = RockSwapFeature()

	@JvmField
	val CRYSTAL_CLUSTER = CrystalClusterFeature()

	@JvmField
	val FLOATING_ISLE = FloatingIsleFeature()

	@JvmField
	val ABYSS_WELL = AbyssWellFeature()

	@JvmField
	val FALLEN_LOG = FallenLogFeature()
	val DEAD_TREE = DeadTreeFeature()

	@JvmField
	val HAMLET = HamletFeature()

	@JvmField
	val WILD_TREE = WildTreeFeature()

	@JvmField
	val WILD_ROCK = WildRockFeature()

	@JvmField
	val CLIFF_FALLS = CliffFallsFeature()

	fun register() {
		ModFeatures.register("cliff_falls", CLIFF_FALLS)
		ModFeatures.register("floating_isle", FLOATING_ISLE)
		ModFeatures.register("abyss_well", ABYSS_WELL)
		ModFeatures.register("fallen_log", FALLEN_LOG)
		ModFeatures.register("dead_tree", DEAD_TREE)
		ModFeatures.register("hamlet", HAMLET)
		ModFeatures.register("wild_tree", WILD_TREE)
		ModFeatures.register("wild_rock", WILD_ROCK)
		ModFeatures.register("giant_tree", GIANT_TREE)
		ModFeatures.register("rock_swap", ROCK_SWAP)
		ModFeatures.register("crystal_cluster", CRYSTAL_CLUSTER)
		ModFeatures.register("flower_strips", FLOWER_STRIPS)
		ModFeatures.register("windmill", WINDMILL)
		ModFeatures.register("landform", LANDFORM)
		ModFeatures.register("umbrella_tree", UMBRELLA_TREE)
		ModFeatures.register("bottle_tree", BOTTLE_TREE)
	}
}
