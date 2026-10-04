/*
 * © Project Lumina 2026 — GPLv3 Licensed
 * You may use, modify, and share this code under the GPL.
 *
 * Just know: changing names and colors doesn't make you a developer.
 * Think before you fork. Build something real — or don't bother.
 */

package com.project.lumina.client.game.module.impl.motion

import com.project.lumina.client.constructors.Element
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.game.InterceptablePacket
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.entity.EntityEventType
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import kotlin.math.sqrt

/**
 * Port de BypassFly (LynxClient): simula vuelo con SetEntityMotionPacket
 * (vertical constante tipo glide + empuje horizontal por WASD), con una
 * ventana de "hold-off" de 650ms tras recibir un golpe - durante esa
 * ventana no se manda movimiento inventado, se deja que la fisica real del
 * cliente (gravedad) tome el control, para no pelear con el knockback real
 * del servidor.
 *
 * Esta version YA INCLUYE el fix que tuve que hacerle a la de LynxClient:
 * al terminar el hold-off, el suavizado se siembra con la velocidad real
 * observada (localPlayer.motionY/X/Z) en vez de perderse a null. Sin esto,
 * el primer paquete tras el hold-off reporta la velocidad objetivo completa
 * de un salto, sin transicion - un cambio de velocidad fisicamente
 * imposible justo en el instante en que el servidor mas atencion le presta
 * a la fisica del jugador (justo tras un golpe). Verificado numericamente
 * en LynxClient: tras 650ms cayendo la velocidad real ronda -0.92
 * bloques/tick; sin este fix se reportaria -0.1266 (el glide) de golpe.
 */
class BypassFlyElement : Element(
    name = "BypassFly",
    category = CheatCategory.Motion
) {

    private var hSpeed by floatValue("H-Speed", 1.1f, 0.2f..3f)
    private var vSpeed by floatValue("V-Speed", 1f, 0.2f..3f)
    private var glide by floatValue("Glide", -0.1266f, -0.3f..0f)
    private var holdOffMs by floatValue("Hold-Off MS", 650f, 0f..2000f)

    private var smoothedSpeed: Float? = null
    private var smoothedVy: Float? = null
    private var holdOffUntilNs = 0L
    private var wasHoldingOff = false

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet

        if (packet is EntityEventPacket &&
            packet.runtimeEntityId == session.localPlayer.runtimeEntityId &&
            packet.type == EntityEventType.HURT
        ) {
            holdOffUntilNs = System.nanoTime() + (holdOffMs * 1_000_000L).toLong()
            return
        }

        if (packet !is PlayerAuthInputPacket) return
        tick(packet, System.nanoTime())
    }

    private fun tick(packet: PlayerAuthInputPacket, now: Long) {
        val holdingOff = now < holdOffUntilNs
        if (holdingOff) {
            wasHoldingOff = true
            return
        }

        if (wasHoldingOff) {
            // el hold-off acaba de terminar: sembrar con la velocidad REAL
            // observada en vez de con null, para que el suavizado de abajo
            // arranque desde donde el jugador esta de verdad
            val lp = session.localPlayer
            smoothedVy = lp.motionY
            smoothedSpeed = sqrt(lp.motionX * lp.motionX + lp.motionZ * lp.motionZ)
            wasHoldingOff = false
        }

        val input = packet.inputData
        val w = input.contains(PlayerAuthInputData.UP)
        val a = input.contains(PlayerAuthInputData.LEFT)
        val s = input.contains(PlayerAuthInputData.DOWN)
        val d = input.contains(PlayerAuthInputData.RIGHT)
        val up = input.contains(PlayerAuthInputData.JUMPING)
        val down = input.contains(PlayerAuthInputData.SNEAKING)
        val moving = w || a || s || d

        var vy = glide
        if (up) vy += vSpeed
        if (down) vy -= vSpeed
        val smoothVy = (smoothedVy ?: vy).let { it + (vy - it) * 0.6f }
        smoothedVy = smoothVy

        if (!moving) {
            smoothedSpeed = (smoothedSpeed ?: 0f).let { it + (0f - it) * 0.6f }
            sendMotion(0f, smoothVy, 0f)
            return
        }

        val yaw = packet.rotation.y
        val off = if (w) {
            if (a) -45f else if (d) 45f else 0f
        } else if (s) {
            if (a) -135f else if (d) 135f else 180f
        } else {
            if (a) -90f else if (d) 90f else 0f
        }
        val rad = Math.toRadians((yaw + off + 90f).toDouble())
        val smoothSpeed = (smoothedSpeed ?: hSpeed).let { it + (hSpeed - it) * 0.6f }
        smoothedSpeed = smoothSpeed

        sendMotion(
            kotlin.math.cos(rad).toFloat() * smoothSpeed,
            smoothVy,
            kotlin.math.sin(rad).toFloat() * smoothSpeed
        )
    }

    private fun sendMotion(x: Float, y: Float, z: Float) {
        session.clientBound(SetEntityMotionPacket().apply {
            runtimeEntityId = session.localPlayer.runtimeEntityId
            motion = Vector3f.from(x, y, z)
        })
    }

    override fun onEnabled() {
        super.onEnabled()
        smoothedSpeed = null
        smoothedVy = null
        holdOffUntilNs = 0L
        wasHoldingOff = false
    }

    override fun onDisabled() {
        super.onDisabled()
        smoothedSpeed = null
        smoothedVy = null
        holdOffUntilNs = 0L
        wasHoldingOff = false
    }
}
