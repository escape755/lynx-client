/*
 * © Project Lumina 2026 — GPLv3 Licensed
 * You may use, modify, and share this code under the GPL.
 *
 * Just know: changing names and colors doesn't make you a developer.
 * Think before you fork. Build something real — or don't bother.
 */

package com.project.lumina.client.game.module.impl.combat

import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Motor de rotacion "Unified" - extraido y recortado de LynxAuraXRots.kt
 * (LynxClient), que en su version original tenia 14 modos de rotacion
 * portados de GatoAuraXRots (cliente PC). Unified era uno solo de esos 14;
 * aqui solo se trae ESE, sin los otros 13 ni los campos de Ctx que solo
 * ellos usaban (confirmado leyendo unified()+baseRot() linea por linea:
 * no tocan width/yaw de Target, ni las teclas/timeSec de Env, ni ninguno
 * de los ~10 parametros numericos legacy del Ctx original).
 *
 * Mejora ya incluida (no estaba en la version original del port): la
 * velocidad relativa objetivo-jugador se suaviza con una media movil
 * exponencial antes de usarse para predecir posicion. Antes se usaba el
 * delta de posicion crudo de un solo paquete (Entity.motionX/Y/Z), que
 * salta de valor si los paquetes llegan a intervalos irregulares - misma
 * razon por la que PredictiveAim (el otro modo) construye su propio
 * historial de posiciones en vez de fiarse del motion crudo.
 */
object UnifiedRotation {

    class Ctx(
        val vertOffset: Float = 0.3f
    ) {
        var rotPitch = 0f
        var rotYaw = 0f
        var headYaw = 0f

        // Velocidad relativa suavizada - persiste entre ticks, igual que rotPitch/rotYaw.
        var smoothDVX = 0f
        var smoothDVY = 0f
        var smoothDVZ = 0f
        var smoothVelInit = false
    }

    class Target(
        val posX: Float, val posY: Float, val posZ: Float,
        val velX: Float, val velY: Float, val velZ: Float,
        val height: Float
    )

    class Env(
        val lpX: Float, val lpY: Float, val lpZ: Float,
        val lpVX: Float, val lpVY: Float, val lpVZ: Float
    )

    private fun baseRot(ctx: Ctx, dx: Float, dy: Float, dz: Float): Pair<Float, Float> {
        val dist = sqrt((dy.toDouble() * dy) + (dx.toDouble() * dx) + (dz.toDouble() * dz))
        var pitch = ctx.rotPitch
        var yaw = ctx.rotYaw
        if (dist > 0.001f) {
            pitch = (asin(dy / dist) * -57.295776f).toFloat()
            yaw = (-atan2(dx, dz) * 57.295776f).toFloat()
        }
        return pitch to yaw
    }

    private fun asin(d: Double): Double = kotlin.math.asin(d.coerceIn(-1.0, 1.0))

    fun unified(ctx: Ctx, t: Target, env: Env) {
        val dx0 = t.posX - env.lpX
        val dy0 = t.posY - env.lpY
        val dz0 = t.posZ - env.lpZ
        val dist = sqrt((dx0 * dx0 + dy0 * dy0 + dz0 * dz0).toDouble()).toFloat()

        val k = (dist * 0.12f).coerceAtMost(4f)

        val rawDVX = t.velX - env.lpVX
        val rawDVY = t.velY - env.lpVY
        val rawDVZ = t.velZ - env.lpVZ
        if (!ctx.smoothVelInit) {
            ctx.smoothDVX = rawDVX; ctx.smoothDVY = rawDVY; ctx.smoothDVZ = rawDVZ
            ctx.smoothVelInit = true
        } else {
            val a = 0.4f
            ctx.smoothDVX += (rawDVX - ctx.smoothDVX) * a
            ctx.smoothDVY += (rawDVY - ctx.smoothDVY) * a
            ctx.smoothDVZ += (rawDVZ - ctx.smoothDVZ) * a
        }
        val dVX = ctx.smoothDVX
        val dVY = ctx.smoothDVY
        val dVZ = ctx.smoothDVZ
        val aimX = t.posX + dVX * k
        val aimY = t.posY + dVY * k + t.height * 0.55f + ctx.vertOffset
        val aimZ = t.posZ + dVZ * k

        val (pitch0, yaw0) = baseRot(ctx, aimX - env.lpX, aimY - env.lpY, aimZ - env.lpZ)
        var pitch = pitch0
        var yaw = yaw0
        while (pitch > 90f) pitch -= 180f
        while (pitch < -90f) pitch += 180f
        while (yaw > 180f) yaw -= 360f
        while (yaw < -180f) yaw += 360f

        var dPitch = pitch - ctx.rotPitch
        var dYaw = yaw - ctx.rotYaw
        while (dYaw > 180f) dYaw -= 360f
        while (dYaw < -180f) dYaw += 360f

        val ff = (7.5f / (dist + 3f)).coerceIn(0.12f, 0.55f)

        var stepYaw = dYaw * ff
        var stepPitch = dPitch * ff

        val maxStep = 20f + (dist * 1.5f).coerceAtMost(20f)
        stepYaw = stepYaw.coerceIn(-maxStep, maxStep)
        stepPitch = stepPitch.coerceIn(-maxStep * 0.6f, maxStep * 0.6f)

        var newYaw = ctx.rotYaw + stepYaw
        var newPitch = ctx.rotPitch + stepPitch
        while (newPitch > 90f) newPitch -= 180f
        while (newPitch < -90f) newPitch += 180f
        while (newYaw > 180f) newYaw -= 360f
        while (newYaw < -180f) newYaw += 360f

        ctx.rotPitch = newPitch
        ctx.rotYaw = newYaw
        ctx.headYaw = newYaw
    }
}
