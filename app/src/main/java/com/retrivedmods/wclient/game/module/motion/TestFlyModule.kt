package com.retrivedmods.wclient.game.module.motion

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket
import kotlin.math.cos
import kotlin.math.sin

/**
 * Estilo de vuelo "Superman": glide vertical constante + empuje horizontal
 * en la direccion que estes mirando/moviendo. Idea tomada del modulo
 * Test-Fly de Veyra (Movement) - mismo concepto (glide + empuje direccional
 * simple, sin fases ni rampas), reescrito desde cero sobre el mecanismo de
 * input que ya usa BypassFlyModule en este proyecto (PlayerAuthInputData /
 * SetEntityMotionPacket), ya que Veyra lee teclado directo (es un cliente
 * nativo por hooking) y aqui no hay tal cosa - se lee la intencion de
 * movimiento ya resuelta en el paquete, igual que el resto de modulos de
 * este cliente.
 *
 * A proposito NO se porta: el "Bypass"/spam de W de Veyra (alternar el flag
 * de avanzar en un intervalo aleatorio para que el input parezca humano) -
 * eso es una tecnica de evasion de deteccion, justo lo que se pidio evitar
 * en este proyecto. Este modulo es solo el movimiento en si.
 */
class TestFlyModule : Module("Test-Fly", ModuleCategory.Motion) {

    private var hSpeed by floatValue("H-Speed", 1.1f, 0.2f..3f)
    private var vSpeed by floatValue("V-Speed", 1f, 0.2f..3f)
    private var glide by floatValue("Glide", -0.14f, -0.3f..0f)

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet as? PlayerAuthInputPacket ?: return
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

        if (!moving) {
            sendMotion(0f, vy, 0f)
            return
        }

        // mismo esquema de offset por combinacion WASD que ya usa
        // BypassFlyModule, para no introducir un segundo estilo de calculo
        // de angulo (atan2) en el mismo proyecto sin necesidad.
        val yaw = packet.rotation.y
        val off = if (w) {
            if (a) -45f else if (d) 45f else 0f
        } else if (s) {
            if (a) -135f else if (d) 135f else 180f
        } else {
            if (a) -90f else if (d) 90f else 0f
        }
        val rad = Math.toRadians((yaw + off + 90f).toDouble())

        sendMotion(cos(rad).toFloat() * hSpeed, vy, sin(rad).toFloat() * hSpeed)
    }

    private fun sendMotion(x: Float, y: Float, z: Float) {
        session.clientBound(SetEntityMotionPacket().apply {
            runtimeEntityId = session.localPlayer.runtimeEntityId
            motion = Vector3f.from(x, y, z)
        })
    }
}
