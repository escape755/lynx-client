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
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import kotlin.math.cos
import kotlin.math.sin

/**
 * Test-Fly: glide vertical constante + empuje horizontal en la direccion
 * que mires, con la velocidad horizontal reducida mientras subes o bajas
 * activamente (Up/Down H Factor). Misma idea base de siempre - lo unico
 * que cambia es COMO se calcula la direccion horizontal:
 *
 * v2: en vez de reconstruir la direccion desde los 8 combos discretos de
 * W/A/S/D (tabla de offsets fija), se usa packet.motion directo - el
 * vector de movimiento analogico que el juego ya calculo para este tick
 * (confirmado que existe en PlayerAuthInputPacket, mismo protocolo). Idea
 * de MuCuteClient (Fly2Module): es mas preciso (input analogo real, no
 * solo 8 direcciones fijas) y mas simple (una rotacion de vector en vez de
 * una tabla de casos). La logica de velocidad/BPS/H-Factor no cambio.
 */
class TestFlyElement : Element(
    name = "Test-Fly",
    category = CheatCategory.Motion
) {

    private var hSpeedBps by floatValue("H Speed BPS", 20f, 2f..60f)
    private var upSpeedBps by floatValue("Up Speed BPS", 14f, 2f..60f)
    private var downSpeedBps by floatValue("Down Speed BPS", 20f, 2f..60f)
    private var glide by floatValue("Glide", -0.08f, -0.3f..0f)
    private var upHFactor by floatValue("Up H Factor", 0.55f, 0f..1f)
    private var downHFactor by floatValue("Down H Factor", 0.44f, 0f..1f)

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet as? PlayerAuthInputPacket ?: return
        val input = packet.inputData

        val up = input.contains(PlayerAuthInputData.JUMPING)
        val down = input.contains(PlayerAuthInputData.SNEAKING)
        val moveX = packet.motion.x
        val moveZ = packet.motion.y

        val vy: Float
        val hFactor: Float
        if (up) {
            vy = upSpeedBps / 20f
            hFactor = upHFactor
        } else if (down) {
            vy = -(downSpeedBps / 20f)
            hFactor = downHFactor
        } else {
            vy = glide
            hFactor = 1f
        }

        if (moveX == 0f && moveZ == 0f) {
            sendMotion(0f, vy, 0f)
            return
        }

        val yaw = Math.toRadians(packet.rotation.y.toDouble())
        val sinYaw = sin(yaw)
        val cosYaw = cos(yaw)

        // packet.motion ya viene normalizado por el juego (strafe, forward);
        // se rota a espacio del mundo con el yaw actual, igual que hace el
        // juego real para decidir hacia donde caminas.
        val forwardX = (-sinYaw * moveX + cosYaw * moveZ).toFloat()
        val forwardZ = (cosYaw * moveX + sinYaw * moveZ).toFloat()
        val moveLen = kotlin.math.sqrt(forwardX * forwardX + forwardZ * forwardZ)
        val hSpeed = (hSpeedBps / 20f) * hFactor

        if (moveLen < 0.001f) {
            sendMotion(0f, vy, 0f)
            return
        }

        sendMotion((forwardX / moveLen) * hSpeed, vy, (forwardZ / moveLen) * hSpeed)
    }

    private fun sendMotion(x: Float, y: Float, z: Float) {
        session.clientBound(SetEntityMotionPacket().apply {
            runtimeEntityId = session.localPlayer.runtimeEntityId
            motion = Vector3f.from(x, y, z)
        })
    }
}
