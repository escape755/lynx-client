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
 * en la direccion que estes mirando/moviendo, con la velocidad horizontal
 * reducida mientras subes o bajas activamente (Up/Down H Factor) - mismos
 * seis parametros (H/Up/Down Speed BPS, Glide, Up/Down H Factor) que un
 * Testfly de referencia que me pasaron por capturas de su GUI (no tenia el
 * codigo, solo los nombres/valores de los sliders - la mecanica de abajo es
 * mi propia interpretacion razonable de esos nombres, no una copia de logica
 * que nunca vi).
 *
 * "BPS" = bloques por segundo; Bedrock corre a 20 ticks/s, así que se
 * divide entre 20 para sacar el valor por tick que de verdad usa
 * SetEntityMotionPacket (igual que el resto de modulos de vuelo del
 * proyecto, todos en blocks/tick).
 *
 * A proposito NO se porta ningun spam de tecla ni ajuste de posicion para
 * camuflar el input como humano - eso es evasion de deteccion, fuera del
 * alcance pedido para este proyecto.
 */
class TestFlyModule : Module("Test-Fly", ModuleCategory.Motion) {

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

        val w = input.contains(PlayerAuthInputData.UP)
        val a = input.contains(PlayerAuthInputData.LEFT)
        val s = input.contains(PlayerAuthInputData.DOWN)
        val d = input.contains(PlayerAuthInputData.RIGHT)
        val up = input.contains(PlayerAuthInputData.JUMPING)
        val down = input.contains(PlayerAuthInputData.SNEAKING)
        val moving = w || a || s || d

        // vertical: ascender/descender manda, si no hay ninguno de los dos
        // se queda en el glide constante. hFactor acompaña a cual de los
        // dos este activo (1.0 = velocidad horizontal completa cuando solo
        // estas gliding a nivel).
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
        val hSpeed = (hSpeedBps / 20f) * hFactor

        sendMotion(cos(rad).toFloat() * hSpeed, vy, sin(rad).toFloat() * hSpeed)
    }

    private fun sendMotion(x: Float, y: Float, z: Float) {
        session.clientBound(SetEntityMotionPacket().apply {
            runtimeEntityId = session.localPlayer.runtimeEntityId
            motion = Vector3f.from(x, y, z)
        })
    }
}
