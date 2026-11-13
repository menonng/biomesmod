package io.github.menonng.biomesmod.terrain.landform

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import net.minecraft.core.BlockPos
import net.minecraft.util.RandomSource
import net.minecraft.world.level.WorldGenLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SlabBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.SlabType
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.levelgen.feature.Feature
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * `biomesmod:hamlet`: the small things people build in a place, generated block by block (no templates), every one
 * different. `kind` picks the family:
 *
 * * `floating_village`: rafts on the sea tied together by walkways: houses, fish pens, store rafts (Ha Long Bay).
 * * `stilt_huts`: thatched huts on stilts over water or swamp (the varzea, the Everglades).
 * * `ger_camp`: round felt tents of the steppe with a fire between them.
 * * `cairns`: cairns, standing stones and the roofless walls of a stone hut (mountains, moors, the puna).
 * * `research_huts`: bright boxes on legs with a mast and fuel drums (polar stations).
 * * `tents`: low dark goat-hair tents with carpets and a fire (deserts).
 *
 * All of it stays within 12 blocks of the origin. Use with `rarity_filter`, `in_square`, `heightmap` and `biome`.
 */
class HamletFeature : Feature<HamletFeature.Config>(Config.CODEC) {
	data class Config(val kind: String) : FeatureConfiguration {
		companion object {
			@JvmField
			val CODEC: Codec<Config> = RecordCodecBuilder.create { i ->
				i.group(Codec.STRING.fieldOf("kind").forGetter(Config::kind)).apply(i) { kind -> Config(kind) }
			}
		}
	}

	override fun place(context: FeaturePlaceContext<Config>): Boolean {
		val b = Builder(context.level(), context.random())
		val o = context.origin()
		val ok = when (context.config().kind) {
			"floating_village" -> b.floatingVillage(o.x, o.z)
			"stilt_huts" -> b.stiltHuts(o.x, o.z)
			"ger_camp" -> b.gerCamp(o.x, o.z)
			"cairns" -> b.cairns(o.x, o.z)
			"research_huts" -> b.researchHuts(o.x, o.z)
			"tents" -> b.tents(o.x, o.z)
			else -> false
		}
		b.finish()
		return ok
	}

	private class Builder(val level: WorldGenLevel, val r: RandomSource) {
		private val p = BlockPos.MutableBlockPos()
		private val connect = ArrayList<BlockPos>()

		fun get(x: Int, y: Int, z: Int): BlockState = level.getBlockState(p.set(x, y, z))

		fun put(x: Int, y: Int, z: Int, s: BlockState) {
			level.setBlock(p.set(x, y, z), s, 2)
		}

		/** A block whose shape depends on its neighbours (fences, walls, bars): connected at the end. */
		fun post(x: Int, y: Int, z: Int, s: BlockState) {
			put(x, y, z, s)
			connect.add(BlockPos(x, y, z))
		}

		fun finish() {
			for (q in connect) {
				val s = level.getBlockState(q)
				level.setBlock(q, Block.updateFromNeighbourShapes(s, level, q), 2)
			}
		}

		/** First free Y above the surface (which may be water). */
		fun surface(x: Int, z: Int): Int = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z)

		/** First free Y above the solid ground or sea floor. */
		fun floor(x: Int, z: Int): Int = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z)

		fun water(x: Int, y: Int, z: Int): Boolean = ChunkWriter.isWater(get(x, y, z))

		fun pick(vararg ids: String): BlockState = B.of(ids[r.nextInt(ids.size)])

		fun slab(id: String, top: Boolean, wet: Boolean = false): BlockState {
			var s = B.of(id).setValue(SlabBlock.TYPE, if (top) SlabType.TOP else SlabType.BOTTOM)
			if (wet) {
				s = s.setValue(BlockStateProperties.WATERLOGGED, true)
			}
			return s
		}

		/** True if the ground under a (2 rx + 1) x (2 rz + 1) footprint is dry and level within [tol] blocks. */
		fun flat(x: Int, z: Int, rx: Int, rz: Int, tol: Int): Boolean {
			val h = surface(x, z)
			for (dx in intArrayOf(-rx, 0, rx)) {
				for (dz in intArrayOf(-rz, 0, rz)) {
					val s = surface(x + dx, z + dz)
					if (abs(s - h) > tol || s != floor(x + dx, z + dz) || !ChunkWriter.isTerrain(get(x + dx, s - 1, z + dz))) {
						return false
					}
				}
			}
			return true
		}

		/** Makes a level pad at [y] - 1 (fills hollows with [fill], clears three blocks above). */
		fun pad(x0: Int, x1: Int, z0: Int, z1: Int, y: Int, fill: BlockState) {
			for (x in x0..x1) {
				for (z in z0..z1) {
					var yy = y - 1
					while (yy > y - 5 && !ChunkWriter.isTerrain(get(x, yy, z))) {
						put(x, yy, z, fill)
						yy--
					}
					for (k in 0..3) {
						if (!get(x, y + k, z).isAir) {
							put(x, y + k, z, AIR)
						}
					}
				}
			}
		}

		/**
		 * A small house with its floor at [y] - 1 already in place: corner posts, walls two high with a doorway and a
		 * window hole, and a low two-step roof with eaves.
		 */
		fun hut(x0: Int, x1: Int, z0: Int, z1: Int, y: Int, wall: BlockState, corner: BlockState, roofSlab: String, ridge: BlockState) {
			val doorX = x0 + 1 + r.nextInt(max(1, x1 - x0 - 1))
			val doorSouth = r.nextBoolean()
			for (x in x0..x1) {
				for (z in z0..z1) {
					val edgeX = x == x0 || x == x1
					val edgeZ = z == z0 || z == z1
					if (!edgeX && !edgeZ) {
						continue
					}
					for (k in 0..1) {
						val door = x == doorX && z == (if (doorSouth) z1 else z0)
						val window = k == 1 && !edgeZ && z == (z0 + z1) / 2
						if (door || window) {
							put(x, y + k, z, AIR)
						} else {
							put(x, y + k, z, if (edgeX && edgeZ) corner else wall)
						}
					}
				}
			}
			for (x in x0 - 1..x1 + 1) {
				for (z in z0 - 1..z1 + 1) {
					val inner = z > z0 && z < z1
					put(x, y + 2, z, if (inner) ridge else slab(roofSlab, false))
					if (inner && abs(z - (z0 + z1) / 2.0) < 1.0) {
						put(x, y + 3, z, slab(roofSlab, false))
					}
				}
			}
		}

		// ---- floating village ----

		fun floatingVillage(ox: Int, oz: Int): Boolean {
			val top = surface(ox, oz) - 1
			if (!water(ox, top, oz) || !water(ox, top - 2, oz)) {
				return false
			}
			for (k in 0 until 8) {
				val a = k * Math.PI / 4
				val x = ox + (cos(a) * 11).roundToInt()
				val z = oz + (sin(a) * 11).roundToInt()
				if (surface(x, z) - 1 != top || !water(x, top, z)) {
					return false // open water all round
				}
			}
			val wood = arrayOf("spruce", "oak", "jungle", "mangrove")[r.nextInt(4)]
			val deck = "${wood}_slab"
			val n = 3 + r.nextInt(3)
			val a0 = r.nextDouble() * Math.PI * 2
			var px = ox
			var pz = oz
			for (k in 0 until n) {
				val a = a0 + k * Math.PI * 2 / n + r.nextDouble() * 0.5
				val d = if (k == 0) 0.0 else 5.0 + r.nextInt(3)
				val cx = ox + (cos(a) * d).roundToInt()
				val cz = oz + (sin(a) * d).roundToInt()
				// Walkway of single planks from the previous raft.
				if (k > 0) {
					val steps = max(abs(cx - px), abs(cz - pz))
					for (s in 0..steps) {
						val x = px + ((cx - px) * s / max(1, steps).toDouble()).roundToInt()
						val z = pz + ((cz - pz) * s / max(1, steps).toDouble()).roundToInt()
						if (water(x, top, z)) {
							put(x, top, z, slab(deck, true, true))
						}
					}
				}
				raft(cx, cz, top, deck, wood, if (k == 0) 0 else r.nextInt(3))
				px = cx
				pz = cz
			}
			return true
		}

		private fun raft(cx: Int, cz: Int, top: Int, deck: String, wood: String, kind: Int) {
			val rx = if (kind == 0) 2 + r.nextInt(2) else 1 + r.nextInt(2)
			val rz = if (kind == 0) 2 else 1 + r.nextInt(2)
			for (x in cx - rx..cx + rx) {
				for (z in cz - rz..cz + rz) {
					val edge = abs(x - cx) == rx || abs(z - cz) == rz
					if (kind == 1 && !edge) {
						continue // fish pen: a frame of planks round open water
					}
					if (water(x, top, z) || get(x, top, z).block is SlabBlock) {
						put(x, top, z, slab(deck, true, true))
					}
				}
			}
			when (kind) {
				0 -> {
					// House: painted walls, a low roof, a lantern at the door.
					val wall = pick("${wood}_planks", "cyan_terracotta", "light_blue_terracotta", "green_terracotta", "white_terracotta", "${wood}_planks")
					val hx = rx - 1
					for (x in cx - hx..cx + hx) {
						for (z in cz - 1..cz + 1) {
							put(x, top, z, B.of("${wood}_planks"))
						}
					}
					hut(cx - hx, cx + hx, cz - 1, cz + 1, top + 1, wall, B.of("stripped_${wood}_log"), deck, pick("${wood}_planks", "cut_copper", "light_gray_terracotta"))
					post(cx + rx, top + 1, cz + rz, B.of("${wood}_fence"))
					put(cx + rx, top + 2, cz + rz, B.of("lantern"))
				}
				1 -> {
					// Fish pen: posts at the corners.
					for (sx in intArrayOf(-rx, rx)) {
						for (sz in intArrayOf(-rz, rz)) {
							post(cx + sx, top + 1, cz + sz, B.of("${wood}_fence"))
						}
					}
				}
				else -> {
					// Store raft: barrels, a hay bale, a drying rack.
					put(cx, top + 1, cz, B.of("barrel"))
					if (r.nextBoolean()) put(cx - rx, top + 1, cz, pick("barrel", "hay_block", "composter"))
					if (r.nextBoolean()) put(cx, top + 2, cz, B.of("barrel"))
					post(cx + rx, top + 1, cz - rz, B.of("${wood}_fence"))
					post(cx + rx, top + 2, cz - rz, B.of("${wood}_fence"))
					if (rz > 1) {
						post(cx + rx, top + 1, cz + rz, B.of("${wood}_fence"))
						post(cx + rx, top + 2, cz + rz, B.of("${wood}_fence"))
					}
				}
			}
		}

		// ---- stilt huts ----

		fun stiltHuts(ox: Int, oz: Int): Boolean {
			var built = 0
			val n = 1 + r.nextInt(3)
			for (k in 0 until n) {
				val cx = ox + (if (k == 0) 0 else r.nextInt(17) - 8)
				val cz = oz + (if (k == 0) 0 else r.nextInt(17) - 8)
				val s = surface(cx, cz)
				val depth = s - floor(cx, cz)
				if (depth > 7 || (depth == 0 && !ChunkWriter.isTerrain(get(cx, s - 1, cz)))) {
					continue
				}
				val rx = 2 + r.nextInt(2)
				val y = s + 2 + r.nextInt(2)
				var clear = true
				for (x in cx - rx - 1..cx + rx + 1) {
					for (z in cz - 3..cz + 3) {
						if (surface(x, z) > y - 1) {
							clear = false
						}
					}
				}
				if (!clear) {
					continue
				}
				val wood = arrayOf("jungle", "mangrove", "spruce")[r.nextInt(3)]
				for (x in cx - rx - 1..cx + rx + 1) {
					for (z in cz - 2..cz + 2) {
						put(x, y - 1, z, B.of("${wood}_planks"))
					}
				}
				for (sx in intArrayOf(-rx - 1, rx + 1)) {
					for (sz in intArrayOf(-2, 2)) {
						var yy = y - 2
						while (yy > y - 16 && !ChunkWriter.isTerrain(get(cx + sx, yy, cz + sz))) {
							put(cx + sx, yy, cz + sz, B.of("${wood}_log"))
							yy--
						}
					}
				}
				hut(cx - rx, cx + rx, cz - 1, cz + 1, y, pick("${wood}_planks", "bamboo_planks", "stripped_${wood}_log"), B.of("${wood}_log"),
					"${wood}_slab", pick("hay_block", "hay_block", "bamboo_mosaic"))
				built++
			}
			return built > 0
		}

		// ---- gers ----

		fun gerCamp(ox: Int, oz: Int): Boolean {
			var built = 0
			val n = 2 + r.nextInt(2)
			val a0 = r.nextDouble() * Math.PI * 2
			for (k in 0 until n) {
				val a = a0 + k * Math.PI * 2 / n
				val cx = ox + (cos(a) * 7).roundToInt()
				val cz = oz + (sin(a) * 7).roundToInt()
				if (!flat(cx, cz, 3, 3, 1)) {
					continue
				}
				val y = surface(cx, cz)
				pad(cx - 2, cx + 2, cz - 2, cz + 2, y, B.of("dirt"))
				val band = pick("red_wool", "orange_wool", "blue_wool", "white_wool")
				val doorDx = if (cx > ox) -2 else 2
				for (dx in -2..2) {
					for (dz in -2..2) {
						val ring = max(abs(dx), abs(dz)) == 2 && abs(dx) + abs(dz) < 4
						if (ring) {
							val door = dx == doorDx && dz == 0
							put(cx + dx, y, cz + dz, if (door) AIR else WHITE)
							put(cx + dx, y + 1, cz + dz, if (door) B.of("spruce_planks") else band)
						} else if (max(abs(dx), abs(dz)) < 2) {
							put(cx + dx, y - 1, cz + dz, pick("red_wool", "spruce_planks", "brown_wool"))
							put(cx + dx, y + 2, cz + dz, WHITE)
						}
					}
				}
				put(cx, y + 2, cz, AIR)
				put(cx, y + 3, cz, B.of("spruce_trapdoor")) // the roof ring
				built++
			}
			if (built > 0 && flat(ox, oz, 1, 1, 1)) {
				val y = surface(ox, oz)
				put(ox, y, oz, B.of("campfire"))
				if (r.nextBoolean()) post(ox + 2, y, oz + 1, B.of("spruce_fence"))
				if (r.nextBoolean()) put(ox - 2, y, oz - 1, B.of("hay_block"))
			}
			return built > 0
		}

		// ---- cairns and ruins ----

		fun cairns(ox: Int, oz: Int): Boolean {
			var built = 0
			val n = 1 + r.nextInt(4)
			for (k in 0 until n) {
				val x = ox + (if (k == 0) 0 else r.nextInt(15) - 7)
				val z = oz + (if (k == 0) 0 else r.nextInt(15) - 7)
				val y = surface(x, z)
				if (y != floor(x, z) || !ChunkWriter.isTerrain(get(x, y - 1, z))) {
					continue
				}
				when (r.nextInt(4)) {
					0 -> {
						// A cairn: a heap that narrows to one stone.
						for (dx in -1..1) {
							for (dz in -1..1) {
								if ((dx == 0 || dz == 0) && surface(x + dx, z + dz) <= y) {
									put(x + dx, surface(x + dx, z + dz), z + dz, pick("cobblestone", "andesite", "mossy_cobblestone"))
								}
							}
						}
						put(x, y + 1, z, pick("cobblestone", "andesite"))
						post(x, y + 2, z, pick("cobblestone_wall", "andesite_wall"))
					}
					1 -> {
						// A standing stone.
						val h = 2 + r.nextInt(3)
						val stone = pick("stone", "andesite", "deepslate", "tuff")
						for (i in 0 until h) {
							put(x, y + i, z, stone)
						}
						if (r.nextBoolean()) put(x + 1, y, z, stone)
					}
					else -> {
						// The roofless walls of a stone hut.
						if (!flat(x, z, 2, 2, 1)) {
							continue
						}
						for (dx in -2..2) {
							for (dz in -2..2) {
								if (max(abs(dx), abs(dz)) != 2) {
									continue
								}
								val h = if (dx == 0 && dz == 2) 0 else r.nextInt(4)
								val gy = surface(x + dx, z + dz)
								for (i in 0 until h) {
									put(x + dx, gy + i, z + dz, pick("cobblestone", "cobblestone", "mossy_cobblestone", "stone_bricks", "cracked_stone_bricks"))
								}
							}
						}
					}
				}
				built++
			}
			return built > 0
		}

		// ---- polar station ----

		fun researchHuts(ox: Int, oz: Int): Boolean {
			var built = 0
			val n = 1 + r.nextInt(3)
			for (k in 0 until n) {
				val cx = ox + (if (k == 0) 0 else (k * 2 - 3) * 6)
				val cz = oz + (if (k == 0) 0 else r.nextInt(7) - 3)
				if (!flat(cx, cz, 3, 2, 2)) {
					continue
				}
				val y = surface(cx, cz) + 1 + r.nextInt(2)
				val rx = 2 + r.nextInt(2)
				for (sx in intArrayOf(-rx, rx)) {
					for (sz in intArrayOf(-1, 1)) {
						var yy = y - 2
						while (yy > y - 8 && !ChunkWriter.isTerrain(get(cx + sx, yy, cz + sz))) {
							put(cx + sx, yy, cz + sz, B.of("polished_andesite"))
							yy--
						}
					}
				}
				for (x in cx - rx..cx + rx) {
					for (z in cz - 1..cz + 1) {
						put(x, y - 1, z, B.of("smooth_stone"))
					}
				}
				val wall = pick("red_concrete", "orange_concrete", "yellow_concrete", "green_concrete", "light_blue_concrete", "red_terracotta")
				hut(cx - rx, cx + rx, cz - 1, cz + 1, y, wall, wall, "smooth_stone_slab", B.of("light_gray_concrete"))
				if (k == 0) {
					// Mast.
					val mx = cx + rx + 2
					val my = surface(mx, cz)
					val h = 4 + r.nextInt(4)
					for (i in 0 until h) {
						put(mx, my + i, cz, B.of("iron_bars"))
					}
					put(mx, my + h, cz, B.of("lightning_rod"))
				} else {
					val by = surface(cx, cz + 3)
					put(cx, by, cz + 3, B.of("barrel"))
					if (r.nextBoolean()) put(cx + 1, surface(cx + 1, cz + 3), cz + 3, B.of("barrel"))
				}
				built++
			}
			return built > 0
		}

		// ---- desert tents ----

		fun tents(ox: Int, oz: Int): Boolean {
			var built = 0
			val n = 1 + r.nextInt(3)
			for (k in 0 until n) {
				val cx = ox + (if (k == 0) 0 else r.nextInt(15) - 7)
				val cz = oz + (if (k == 0) 0 else (k * 2 - 3) * 5)
				if (!flat(cx, cz, 3, 2, 1)) {
					continue
				}
				val y = surface(cx, cz)
				val rx = 2 + r.nextInt(2)
				pad(cx - rx, cx + rx, cz - 1, cz + 1, y, B.of("sand"))
				val cloth = pick("black_wool", "brown_wool", "black_wool", "gray_wool")
				val stripe = pick("white_wool", "red_wool", "brown_wool")
				for (x in cx - rx..cx + rx) {
					// Back wall, a roof that sags between the poles, the front left open.
					put(x, y, cz - 1, cloth)
					put(x, y + 1, cz - 1, if (x % 2 == 0) stripe else cloth)
					for (z in cz - 1..cz + 1) {
						put(x, y + 2, z, if ((x - cx) % 3 == 0) stripe else cloth)
					}
					put(x, y, cz, pick("red_carpet", "red_carpet", "orange_carpet", "white_carpet"))
				}
				for (sx in intArrayOf(-rx, rx)) {
					post(cx + sx, y, cz + 1, B.of("oak_fence"))
					post(cx + sx, y + 1, cz + 1, B.of("oak_fence"))
					put(cx + sx, y, cz, cloth)
					put(cx + sx, y + 1, cz, cloth)
				}
				if (k == 0 && surface(cx, cz + 3) == y) {
					put(cx, y, cz + 3, B.of("campfire"))
				}
				built++
			}
			return built > 0
		}

		companion object {
			private val AIR = B.of("air")
			private val WHITE = B.of("white_wool")
		}
	}
}
