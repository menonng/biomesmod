package io.github.menonng.biomesmod.shots

import com.google.gson.JsonParser
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.levelgen.Heightmap
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Dev-only screenshot director for the docs (never shipped). Started with `-Dbiomesmod.shots=<plan.json>` in a client
 * that quick-plays a pre-generated world: for every shot it moves the (spectator) player to the camera position, sets
 * the time of day, waits for terrain, LODs and shaders to settle, hides the HUD and saves
 * `screenshots/<name>.png`. Quits when the plan is done.
 *
 * Plan: `{"settle": 30, "warmup": 60, "client_commands": ["voxy import current"], "shots": [{"name": "salar_de_uyuni", "x": 1, "y": 220, "z": 2, "yaw": 90, "pitch": 12,
 * "time": 1000, "commands": ["..."]}]}`.
 */
class ShotDirector : ClientModInitializer {
	/**
	 * A camera: either absolute (y, yaw, pitch) or relative to the ground: [above] blocks over the surface at (x, z),
	 * looking at (tx, tz), [tAbove] blocks over the surface there.
	 */
	private class Shot(val name: String, val x: Double, val y: Double?, val z: Double, val yaw: Float, val pitch: Float, val above: Double?,
		val tx: Double?, val tz: Double?, val tAbove: Double, val time: Int, val settle: Int, val commands: List<String>)

	private enum class Phase { WAIT_WORLD, WARMUP, MOVE, SETTLE, CAPTURE, AFTER, DONE }

	private val log = LoggerFactory.getLogger("biomesmod-shots")
	private var shots: List<Shot> = listOf()
	private var warmup = 60
	private var clientCommands: List<String> = listOf()
	private var phase = Phase.WAIT_WORLD
	private var index = 0
	private var ticks = 0

	override fun onInitializeClient() {
		val planPath = System.getProperty("biomesmod.shots") ?: return
		val root = JsonParser.parseString(Files.readString(Path.of(planPath))).asJsonObject
		val settle = root.get("settle")?.asInt ?: 30
		warmup = root.get("warmup")?.asInt ?: 60
		// Client-side commands run once after joining, e.g. "voxy import current" to build the LODs from the saved world.
		clientCommands = root.getAsJsonArray("client_commands")?.map { it.asString } ?: listOf()
		val only = System.getProperty("biomesmod.shots.only", "").split(",").filter { it.isNotBlank() }.toSet()
		shots = root.getAsJsonArray("shots").map { it.asJsonObject }.map { o ->
			Shot(o.get("name").asString, o.get("x").asDouble, o.get("y")?.asDouble, o.get("z").asDouble, o.get("yaw")?.asFloat ?: 0f,
				o.get("pitch")?.asFloat ?: 0f, o.get("above")?.asDouble, o.get("tx")?.asDouble, o.get("tz")?.asDouble, o.get("t_above")?.asDouble ?: 0.0,
				o.get("time")?.asInt ?: 1000, o.get("settle")?.asInt ?: settle, o.getAsJsonArray("commands")?.map { it.asString } ?: listOf())
		}.filter { only.isEmpty() || it.name in only }
		log.info("[shots] {} shot(s) planned from {}", shots.size, planPath)
		ClientTickEvents.END_CLIENT_TICK.register { step(it) }
	}

	/** Camera position and angles: resolves ground-relative shots on the server (generating the chunks if needed). */
	private fun resolve(server: MinecraftServer, s: Shot): DoubleArray {
		fun ground(x: Double, z: Double): Int = server.submit<Int> {
			val level = server.overworld()
			val bx = Math.floor(x).toInt()
			val bz = Math.floor(z).toInt()
			level.getChunk(bx shr 4, bz shr 4)
			level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz)
		}.join()
		val y = s.y ?: (ground(s.x, s.z) + (s.above ?: 40.0))
		if (s.tx == null || s.tz == null) {
			return doubleArrayOf(s.x, y, s.z, s.yaw.toDouble(), s.pitch.toDouble())
		}
		val ty = ground(s.tx, s.tz) + s.tAbove
		val dx = s.tx - s.x
		val dz = s.tz - s.z
		val yaw = Math.toDegrees(Math.atan2(-dx, dz))
		val pitch = Math.toDegrees(Math.atan2(y - ty, Math.sqrt(dx * dx + dz * dz)))
		return doubleArrayOf(s.x, y, s.z, yaw, pitch)
	}

	private var camera = DoubleArray(5)

	private fun tp(server: MinecraftServer, name: String) {
		command(server, "tp $name %.2f %.2f %.2f %.2f %.2f".format(java.util.Locale.ROOT, camera[0], camera[1], camera[2], camera[3], camera[4]))
	}

	private fun command(server: MinecraftServer, cmd: String) {
		server.execute { server.commands.performPrefixedCommand(server.createCommandSourceStack(), cmd) }
	}

	private fun step(mc: Minecraft) {
		val player = mc.player
		val server = mc.singleplayerServer
		if (player == null || server == null) {
			return
		}
		val name = player.gameProfile.name
		ticks++
		when (phase) {
			Phase.WAIT_WORLD -> {
				command(server, "gamemode spectator $name")
				command(server, "gamerule advance_time false")
				command(server, "gamerule advance_weather false")
				command(server, "gamerule doDaylightCycle false")
				command(server, "gamerule doWeatherCycle false")
				command(server, "weather clear")
				if (!mc.gui.hud.isHidden) {
					mc.gui.hud.toggle()
				}
				clientCommands.forEach { player.connection.sendCommand(it) }
				ticks = 0
				phase = if (shots.isEmpty()) Phase.DONE else Phase.WARMUP
				log.info("[shots] world joined, warming up for {} s", warmup)
			}
			Phase.WARMUP -> if (ticks >= warmup * 20) {
				phase = Phase.MOVE
			}
			Phase.MOVE -> {
				val s = shots[index]
				command(server, "time set ${s.time}")
				camera = resolve(server, s)
				log.info("[shots] {} at {}", s.name, camera.joinToString { "%.1f".format(java.util.Locale.ROOT, it) })
				tp(server, name)
				s.commands.forEach { command(server, it) }
				ticks = 0
				phase = Phase.SETTLE
			}
			Phase.SETTLE -> {
				val s = shots[index]
				// Keep re-applying the view: the client may still be catching up with the teleport.
				if (ticks % 40 == 0) {
					tp(server, name)
				}
				if (ticks >= s.settle * 20) {
					phase = Phase.CAPTURE
				}
			}
			Phase.CAPTURE -> {
				val s = shots[index]
				log.info("[shots] {}: {} fps", s.name, mc.fps)
				Screenshot.grab(mc.gameDirectory, "${s.name}.png", mc.gameRenderer.mainRenderTarget(), 1) { msg -> log.info("[shots] {}: {}", s.name, msg.string) }
				ticks = 0
				phase = Phase.AFTER
			}
			Phase.AFTER -> if (ticks >= 20) {
				index++
				phase = if (index < shots.size) Phase.MOVE else Phase.DONE
			}
			Phase.DONE -> {
				log.info("[shots] done ({} shot(s))", shots.size)
				phase = Phase.DONE
				mc.stop()
			}
		}
	}
}
