package com.retrivedmods.wclient.game.module.motion

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.entity.EntityEventType
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket
import org.cloudburstmc.protocol.bedrock.packet.LevelChunkPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Port of Gato Client's BypassFly module (game/module/motion/BypassFlyModule.kt).
 *
 * Added on request: a short "hold-off" window that backs the bypass speeds off
 * to normal whenever (a) the local player takes damage (a HURT EntityEvent for
 * our own runtimeEntityId - the server is about to apply its own knockback
 * motion right about then, and our fake motion was fighting it) or (b) a chunk
 * near the player just loaded (LevelChunkPacket within ~2 chunks - collision
 * data for brand new terrain can briefly disagree with what we're claiming).
 * Same idea as the earlier BypassFly smoothing fix, just with two more
 * concrete triggers. Still can't promise zero lagback - depends on the
 * server's own anti-cheat - but these are the two patterns that were named.
 */
class BypassFlyModule : Module("BypassFly", ModuleCategory.Motion) {

    private var initialH by floatValue("Initial Horizontal", 2.42f, 0.1f..3.0f)
    private var initialV by floatValue("Initial Vertical", 1.62f, 0.1f..3.0f)
    private var initBypH by floatValue("Initial Bypass H", 1.1585f, 0.1f..3.0f)
    private var initBypV by floatValue("Initial Bypass V", 1.086f, 0.1f..3.0f)
    private var finalH by floatValue("Final Horizont", 2.18f, 0.1f..3.0f)
    private var finalV by floatValue("Final Vertical", 1.42f, 0.1f..3.0f)
    private var finalBypH by floatValue("Final Bypass H", 1.09f, 0.1f..3.0f)
    private var finalBypV by floatValue("Final Bypass V", 1.05f, 0.1f..3.0f)
    private var glide by floatValue("Glide", -0.1266f, -0.2f..0.2f)
    private var releaseDelay by floatValue("Release Delay", 0.09f, 0.0f..0.5f)
    private var descentSpeed by floatValue("Descent Speed", 4.2997f, 0.1f..5.0f)
    private var descentH by floatValue("Descent Horizontal", 1.1f, 0.1f..3.0f)
    private var descensoAlternado by boolValue("Descenso Alternado", true)
    private var holdOffMs by floatValue("Hold-off (ms)", 650f, 100f..1200f)
    private var chunkRadius by intValue("Chunk Radius", 2, 1..4)

    private var bypassPhase = false
    private var phaseStartNs = 0L
    private var altTick = 0
    private var smoothedSpeed: Float? = null
    private var smoothedVy: Float? = null
    private var holdOffUntilNs = 0L

    override fun onEnabled() {
        super.onEnabled()
        bypassPhase = false
        smoothedSpeed = null
        smoothedVy = null
        holdOffUntilNs = 0L
    }

    override fun onDisabled() {
        super.onDisabled()
        bypassPhase = false
        smoothedSpeed = null
        smoothedVy = null
        holdOffUntilNs = 0L
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionCreated) return
        val packet = interceptablePacket.packet
        val now = System.nanoTime()

        // Trigger 1: acabamos de recibir un golpe - el servidor va a mandar
        // su propio knockback ahora mismo, dejale el camino libre.
        if (packet is EntityEventPacket &&
            packet.type == EntityEventType.HURT &&
            packet.runtimeEntityId == session.localPlayer.runtimeEntityId
        ) {
            holdOffUntilNs = now + (holdOffMs * 1_000_000L).toLong()
        }

        // Trigger 2: cargó un chunk cerca nuestro - la colision del terreno
        // recien llegado puede no coincidir todavia con lo que reportamos.
        if (packet is LevelChunkPacket) {
            val lp = session.localPlayer
            val playerChunkX = floor(lp.posX / 16.0).toInt()
            val playerChunkZ = floor(lp.posZ / 16.0).toInt()
            val dx = packet.chunkX - playerChunkX
            val dz = packet.chunkZ - playerChunkZ
            if (dx * dx + dz * dz <= chunkRadius * chunkRadius) {
                holdOffUntilNs = now + (holdOffMs * 1_000_000L).toLong()
            }
        }

        val authInput = packet as? PlayerAuthInputPacket ?: return
        tick(authInput, now)
    }

    private fun tick(packet: PlayerAuthInputPacket, now: Long) {
        val holdingOff = now < holdOffUntilNs
        if (holdingOff) {
            // no mandamos NADA de movimiento inventado durante esta ventana -
            // que el servidor procese el golpe/knockback sin que nosotros
            // sigamos empujando encima. bypassPhase tambien se resetea para
            // no arrancar una fase nueva a mitad del hold-off.
            bypassPhase = false
            smoothedSpeed = null
            smoothedVy = null
            return
        }

        val input = packet.inputData
        val pos = packet.position

        var t = sqrt(pos.z * pos.z + pos.x * pos.x) / 5500000f
        if (t > 1f) t = 1f
        val bypH = initBypH + (finalBypH - initBypH) * t
        val normH = initialH + (finalH - initialH) * t
        val normV = initialV + (finalV - initialV) * t
        val bypV = initBypV + (finalBypV - initBypV) * t

        val w = input.contains(PlayerAuthInputData.UP)
        val a = input.contains(PlayerAuthInputData.LEFT)
        val s = input.contains(PlayerAuthInputData.DOWN)
        val d = input.contains(PlayerAuthInputData.RIGHT)
        val space = input.contains(PlayerAuthInputData.JUMPING)
        val shift = input.contains(PlayerAuthInputData.SNEAKING)
        val moving = w || a || s || d

        var bypassActive = false
        if (space && moving) {
            bypassPhase = true
            phaseStartNs = now
            bypassActive = true
        } else if (bypassPhase) {
            bypassActive = true
            val secs = (now - phaseStartNs) / 1e9f
            if (secs >= releaseDelay) {
                bypassPhase = false
                bypassActive = false
            }
        }

        val vy: Float = if (shift) {
            -descentSpeed
        } else {
            glide + (if (space) (if (bypassActive) bypV else normV) else 0f)
        }
        val smoothVy = (smoothedVy ?: vy).let { it + (vy - it) * 0.6f }
        smoothedVy = smoothVy

        if (!moving) {
            sendMotion(0f, smoothVy, 0f)
            return
        }

        val yaw = packet.rotation.y
        val off = if (w) {
            (if (a) -45f else (if (d) 45f else 0f))
        } else if (s) {
            (if (a) -135f else (if (d) 135f else 180f))
        } else {
            (if (a) -90f else (if (d) 90f else 0f))
        }
        val rad = Math.toRadians((yaw + off + 90f).toDouble())

        val hit = input.contains(PlayerAuthInputData.HORIZONTAL_COLLISION) ||
                input.contains(PlayerAuthInputData.AUTO_JUMPING_IN_WATER)

        val speed = when {
            shift -> descentH
            hit -> 2.0f
            bypassActive -> bypH
            else -> normH
        }
        val smoothSpeed = (smoothedSpeed ?: speed).let { it + (speed - it) * 0.6f }
        smoothedSpeed = smoothSpeed

        if (shift && descensoAlternado) {
            if (altTick++ % 2 == 0) {
                sendMotion(cos(rad).toFloat() * smoothSpeed, glide, sin(rad).toFloat() * smoothSpeed)
            } else {
                sendMotion(0f, -descentSpeed, 0f)
            }
            return
        }
        sendMotion(cos(rad).toFloat() * smoothSpeed, smoothVy, sin(rad).toFloat() * smoothSpeed)
    }

    private fun sendMotion(x: Float, y: Float, z: Float) {
        session.clientBound(SetEntityMotionPacket().apply {
            runtimeEntityId = session.localPlayer.runtimeEntityId
            motion = Vector3f.from(x, y, z)
        })
    }
}
