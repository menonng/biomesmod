package io.github.menonng.biomesmod.block

import io.github.menonng.biomesmod.ModBlocks
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.EntityBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A geothermal vent. A geyser vent erupts on its own cycle (60 to 180 s, every vent different, derived from its position
 * and the game time so server and clients agree without packets): a boiling column of steam and water up to ~25 blocks
 * that throws anything standing on it into the air, then quiet steaming until the next eruption. A steam vent
 * (fumarole) just breathes sulphurous steam.
 */
class VentBlock(properties: BlockBehaviour.Properties, val kind: Int) : Block(properties), EntityBlock {
	override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = VentBlockEntity(pos, state)

	override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
		if (type === ModBlocks.VENT_ENTITY) BlockEntityTicker { l, p, _, _ -> VentBlockEntity.tick(l, p, kind) } else null

	companion object {
		const val GEYSER = 0
		const val STEAM = 1

		/** Star spring (Star Abyss): a fountain of starlight that lifts whoever steps in up to the floating isles. */
		const val STAR = 2
	}
}

class VentBlockEntity(pos: BlockPos, state: BlockState) : BlockEntity(ModBlocks.VENT_ENTITY, pos, state) {
	companion object {
		private fun mix(pos: BlockPos): Long {
			var h = pos.asLong() * -0x61c8864680b583ebL
			h = h xor (h ushr 29)
			h *= -0x4b47d5b1b6a3c8a7L
			return h xor (h ushr 32)
		}

		/** {ticks since this vent's cycle began, eruption length, cycle length}; it erupts while t < length. */
		fun phase(level: Level, pos: BlockPos): IntArray {
			val h = mix(pos)
			val period = 1200 + Math.floorMod(h, 2400L).toInt()
			val length = 160 + Math.floorMod(h ushr 20, 200L).toInt()
			val t = Math.floorMod(level.gameTime + (h ushr 8), period.toLong()).toInt()
			return intArrayOf(t, length, period)
		}

		fun tick(level: Level, pos: BlockPos, kind: Int) {
			val x = pos.x + 0.5
			val y = pos.y + 1.0
			val z = pos.z + 0.5
			if (kind == VentBlock.STAR) {
				star(level, x, y, z)
				return
			}
			if (kind == VentBlock.STEAM) {
				if (level.isClientSide && level.random.nextInt(3) == 0) {
					val r = level.random
					level.addAlwaysVisibleParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, x + (r.nextDouble() - 0.5) * 0.6, y,
						z + (r.nextDouble() - 0.5) * 0.6, 0.0, 0.04 + r.nextDouble() * 0.05, 0.0)
				}
				return
			}
			val ph = phase(level, pos)
			val t = ph[0]
			val len = ph[1]
			if (t >= len) {
				// Between eruptions: the vent steams and gurgles, harder in the last half minute before it blows.
				if (level.isClientSide) {
					val r = level.random
					val soon = t > ph[2] - 600
					if (r.nextInt(if (soon) 2 else 4) == 0) {
						level.addAlwaysVisibleParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true, x + (r.nextDouble() - 0.5) * 0.5, y,
							z + (r.nextDouble() - 0.5) * 0.5, 0.0, 0.05 + r.nextDouble() * 0.04, 0.0)
					}
					if (r.nextInt(12) == 0) {
						level.addParticle(ParticleTypes.BUBBLE_POP, x + (r.nextDouble() - 0.5), y + 0.05, z + (r.nextDouble() - 0.5), 0.0, 0.02, 0.0)
					}
				}
				return
			}
			// Eruption: ramp up over 1.5 s, full for most of it, then sputter out over the last 3 s.
			val strength = min(1.0, min(t / 30.0, (len - t) / 60.0))
			if (level.isClientSide) {
				val r = level.random
				if (t == 0) {
					level.playLocalSound(x, y, z, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 3f, 0.5f, false)
				}
				if (t % 20 == 5) {
					level.playLocalSound(x, y, z, SoundEvents.BUBBLE_COLUMN_UPWARDS_AMBIENT, SoundSource.BLOCKS, 2.5f, 0.6f, false)
				}
				val n = (2 + 10 * strength).toInt()
				for (k in 0 until n) {
					val a = r.nextDouble() * Math.PI * 2
					val s = r.nextDouble() * 0.25
					val up = (0.55 + 0.75 * r.nextDouble()) * strength
					level.addAlwaysVisibleParticle(ParticleTypes.CLOUD, true, x + cos(a) * s, y, z + sin(a) * s,
						cos(a) * 0.03, up, sin(a) * 0.03)
				}
				// Water thrown out of the column rains back around the cone.
				for (k in 0 until (6 * strength).toInt()) {
					val a = r.nextDouble() * Math.PI * 2
					val s = 0.5 + r.nextDouble() * 3.5
					val hy = y + 2 + r.nextDouble() * 18 * strength
					level.addAlwaysVisibleParticle(ParticleTypes.FALLING_WATER, true, x + cos(a) * s, hy, z + sin(a) * s, 0.0, 0.0, 0.0)
				}
				if (t % 3 == 0) {
					spawn(level, ParticleTypes.CAMPFIRE_SIGNAL_SMOKE, x + (r.nextDouble() - 0.5) * 2, y + 14 * strength + r.nextDouble() * 6,
						z + (r.nextDouble() - 0.5) * 2, 0.08 * strength)
				}
				if (r.nextInt(2) == 0) {
					level.addParticle(ParticleTypes.SPLASH, x + (r.nextDouble() - 0.5) * 3, y + 0.1, z + (r.nextDouble() - 0.5) * 3, 0.0, 0.2, 0.0)
				}
			} else if (strength > 0.5) {
				// Anything standing in the column is thrown upward.
				val box = AABB(x - 0.9, y - 0.2, z - 0.9, x + 0.9, y + 16 * strength, z + 0.9)
				for (e in level.getEntities(null as Entity?, box)) {
					val v = e.deltaMovement
					if (v.y < 1.1) {
						e.setDeltaMovement(v.x, min(1.1, v.y + 0.35), v.z)
						e.hurtMarked = true
						e.resetFallDistance()
					}
				}
			}
		}

		/** Star spring: a slow column of starlight; entities inside rise gently and fall softly. */
		private fun star(level: Level, x: Double, y: Double, z: Double) {
			if (level.isClientSide) {
				val r = level.random
				for (k in 0 until 2) {
					val a = r.nextDouble() * Math.PI * 2
					val s = r.nextDouble() * 0.6
					level.addAlwaysVisibleParticle(ParticleTypes.END_ROD, true, x + cos(a) * s, y + r.nextDouble() * 0.5, z + sin(a) * s,
						0.0, 0.12 + r.nextDouble() * 0.1, 0.0)
				}
				if (r.nextInt(5) == 0) {
					level.addParticle(ParticleTypes.ENCHANT, x + (r.nextDouble() - 0.5) * 2, y + r.nextDouble() * 3, z + (r.nextDouble() - 0.5) * 2,
						0.0, 0.3, 0.0)
				}
				return
			}
			val box = AABB(x - 1.3, y - 0.2, z - 1.3, x + 1.3, y + 28, z + 1.3)
			for (e in level.getEntities(null as Entity?, box)) {
				val v = e.deltaMovement
				if (v.y < 0.42) {
					e.setDeltaMovement(v.x * 0.9, min(0.42, v.y + 0.12), v.z * 0.9)
					e.hurtMarked = true
				}
				e.resetFallDistance()
				if (e is LivingEntity) {
					e.addEffect(MobEffectInstance(MobEffects.SLOW_FALLING, 80))
				}
			}
		}

		private fun spawn(level: Level, p: ParticleOptions, x: Double, y: Double, z: Double, up: Double) {
			level.addAlwaysVisibleParticle(p, true, x, y, z, 0.0, up, 0.0)
		}
	}
}
