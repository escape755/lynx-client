package com.retrivedmods.wclient.game.module.combat

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Motor de rotacion con prediccion de movimiento + suavizado continuo.
 *
 * Es el modo "Predictive" de LynxAura, LynxAuraX y LynxAuraPro (a traves de
 * [PredictiveRotator]). A diferencia de los handlers de [LynxAuraXRots], que
 * calculan la rotacion de cada tick sin memoria, este necesita estado entre
 * ticks (velocidad angular actual + historial de movimiento de los objetivos),
 * asi que cada modulo tiene su propia instancia en vez de usar un `object` global.
 *
 * Pipeline por tick (PlayerAuthInputPacket, ~20 Hz):
 *  1. observe(): estima velocidad/aceleracion vertical y "confianza" de cada
 *     candidato a partir de sus posiciones (no de Entity.motion, que es el delta
 *     por paquete y es ruidoso si los paquetes llegan irregulares).
 *  2. step(): punto de mira = centro del objetivo + adelanto filtrado
 *     (velocidad * tiempo de prediccion * confianza).
 *  3. yaw/pitch deseados -> se acercan con perfil suave: ganancia exponencial +
 *     tope de velocidad angular + tope de aceleracion angular + zona muerta
 *     proporcional al tamano angular del hitbox.
 *
 * Unidades: posiciones en bloques, velocidades en bloques/tick, angulos en grados,
 * velocidades angulares en grados/tick. Convenciones de angulos identicas a
 * LynxAuraXRots.baseRot (yaw = -atan2(dx, dz), pitch negativo = mirar arriba).
 *
 * No incluye aleatoriedad ni ningun mecanismo pensado para eludir validaciones
 * del servidor: es un seguimiento determinista y suave.
 */
class PredictiveAim {

    data class Config(
        /** Cuantos ticks hacia adelante se estima la posicion del objetivo. */
        val predictionTicks: Float = 2.0f,
        /** Multiplicador global del adelanto (0 = sin prediccion). */
        val predictionStrength: Float = 1.0f,
        /** Fraccion del error angular que se cierra por tick (0..1). */
        val smoothing: Float = 0.35f,
        /** Velocidad angular maxima de yaw en grados/tick (pitch usa 60%). */
        val maxYawSpeed: Float = 30f,
        /** Aceleracion angular maxima en grados/tick^2 (pitch usa 60%). */
        val turnAccel: Float = 12f,
        /** Por debajo de esta velocidad (bloques/tick) la prediccion se apaga. */
        val minSpeed: Float = 0.03f,
        /** Tope del adelanto horizontal en bloques. */
        val maxLead: Float = 2.5f
    )

    var config = Config()

    // ---- estado de los ejes emitidos ----
    private class Axis(var angle: Float = 0f, var vel: Float = 0f)

    private val yawAxis = Axis()
    private val pitchAxis = Axis()

    val yaw: Float get() = yawAxis.angle
    val pitch: Float get() = pitchAxis.angle

    /** true mientras estamos sobreescribiendo la rotacion real del cliente. */
    var isOverriding = false
        private set

    private var lastStepTick = Long.MIN_VALUE

    // ---- tracker de movimiento por entidad ----
    private class Motion(var x: Float, var y: Float, var z: Float, var tick: Long) {
        var seenTick = tick
        var vx = 0f
        var vy = 0f
        var vz = 0f
        var ay = 0f
        var confidence = 0f
        var samples = 0
        var leadX = 0f
        var leadY = 0f
        var leadZ = 0f
    }

    private val motions = HashMap<Long, Motion>()

    fun reset() {
        motions.clear()
        yawAxis.angle = 0f; yawAxis.vel = 0f
        pitchAxis.angle = 0f; pitchAxis.vel = 0f
        isOverriding = false
        lastStepTick = Long.MIN_VALUE
    }

    /** Arranca desde la rotacion real de la camara para que no haya salto al adquirir objetivo. */
    fun seed(realYaw: Float, realPitch: Float, tick: Long) {
        yawAxis.angle = wrap180(realYaw); yawAxis.vel = 0f
        pitchAxis.angle = realPitch.coerceIn(-90f, 90f); pitchAxis.vel = 0f
        lastStepTick = tick - 1
        isOverriding = true
    }

    // =====================================================================
    // Estimacion de movimiento
    // =====================================================================

    /** Alimenta el tracker. Idempotente dentro del mismo tick. */
    fun observe(id: Long, x: Float, y: Float, z: Float, tick: Long) {
        val cfg = config
        val m = motions[id]
        if (m == null) {
            motions[id] = Motion(x, y, z, tick)
            return
        }
        if (tick < m.seenTick) { // el contador de ticks se reinicio (mundo nuevo)
            m.seenTick = tick - 1
            m.tick = tick
        }
        if (tick <= m.seenTick) return
        m.seenTick = tick

        val dx = x - m.x
        val dy = y - m.y
        val dz = z - m.z
        val distSq = dx * dx + dy * dy + dz * dz

        if (distSq < 1e-6f) {
            // No llego posicion nueva: mantener un par de ticks (los paquetes de
            // posicion no llegan siempre cada tick) y luego decaer hacia 0.
            if (tick - m.tick > HOLD_TICKS) {
                m.vx *= IDLE_DECAY; m.vy *= IDLE_DECAY; m.vz *= IDLE_DECAY
                m.ay *= IDLE_DECAY
            }
            return
        }

        if (distSq > TELEPORT_DIST * TELEPORT_DIST) { // teleport / respawn: sin historial fiable
            m.vx = 0f; m.vy = 0f; m.vz = 0f; m.ay = 0f
            m.confidence = 0f; m.samples = 0
            m.leadX = 0f; m.leadY = 0f; m.leadZ = 0f
            m.x = x; m.y = y; m.z = z; m.tick = tick
            return
        }

        val span = (tick - m.tick).coerceIn(1L, 6L).toFloat()
        val rvx = dx / span
        val rvy = dy / span
        val rvz = dz / span

        val sp = hypot(m.vx, m.vz)
        val rsp = hypot(rvx, rvz)
        var turned = false
        var braking = false
        if (sp > cfg.minSpeed && rsp > cfg.minSpeed) {
            val cos = (m.vx * rvx + m.vz * rvz) / (sp * rsp)
            turned = cos < TURN_COS
        } else if (sp > cfg.minSpeed * 2f && rsp < sp * BRAKE_RATIO) {
            braking = true
        }

        val a = if (turned || braking) FAST_ALPHA else VEL_ALPHA
        val prevVy = m.vy
        m.vx += (rvx - m.vx) * a
        m.vy += (rvy - m.vy) * a
        m.vz += (rvz - m.vz) * a
        m.ay += (((rvy - prevVy) / span) - m.ay) * AY_ALPHA

        m.confidence = when {
            turned -> min(m.confidence, TURN_CONF)
            braking -> min(m.confidence, BRAKE_CONF)
            else -> min(1f, m.confidence + CONF_RECOVER)
        }
        m.samples++
        m.x = x; m.y = y; m.z = z; m.tick = tick
    }

    /** Descarta el historial de entidades que ya no son candidatas. */
    fun retain(ids: Collection<Long>) {
        if (motions.isEmpty()) return
        motions.keys.retainAll(ids.toSet())
    }

    // =====================================================================
    // Un paso de rotacion hacia el objetivo (posicion en pies, como Target)
    // =====================================================================

    fun step(
        eyeX: Float, eyeY: Float, eyeZ: Float,
        id: Long,
        tx: Float, ty: Float, tz: Float,
        width: Float, height: Float,
        vertOffset: Float,
        tick: Long
    ) {
        val cfg = config
        observe(id, tx, ty, tz, tick)
        val m = motions[id] ?: return
        val dt = consumeDt(tick)
        if (dt == 0) return

        // ---- adelanto (lead) filtrado ----
        val warm = min(1f, m.samples / 3f)
        val strength = cfg.predictionStrength * m.confidence * warm
        val t = cfg.predictionTicks

        val speed = hypot(m.vx, m.vz)
        val gateXZ = smoothstep(cfg.minSpeed, cfg.minSpeed * 3f, speed)
        var lx = m.vx * t * strength * gateXZ
        var lz = m.vz * t * strength * gateXZ
        val lh = hypot(lx, lz)
        if (lh > cfg.maxLead) {
            val s = cfg.maxLead / lh
            lx *= s; lz *= s
        }

        // Vertical: velocidad + gravedad estimada (solo hacia abajo, <= 0.09 b/tick^2)
        val gateY = smoothstep(VY_MIN, VY_MIN * 2.5f, abs(m.vy))
        val ay = if (abs(m.vy) > VY_MIN) m.ay.coerceIn(-GRAVITY, 0f) else 0f
        val ly = ((m.vy * t + 0.5f * ay * t * t) * strength * gateY).coerceIn(-MAX_LEAD_DOWN, MAX_LEAD_UP)

        m.leadX += (lx - m.leadX) * LEAD_ALPHA
        m.leadY += (ly - m.leadY) * LEAD_ALPHA
        m.leadZ += (lz - m.leadZ) * LEAD_ALPHA

        val aimX = tx + m.leadX
        val aimY = ty + height * 0.55f + vertOffset + m.leadY
        val aimZ = tz + m.leadZ

        // ---- angulos deseados ----
        val dx = aimX - eyeX
        val dy = aimY - eyeY
        val dz = aimZ - eyeZ
        val distXZ = hypot(dx, dz)

        // Con el objetivo casi encima el yaw es inestable (atan2 de ~0): se congela suavemente.
        val nearBlend = smoothstep(NEAR_MIN, NEAR_MAX, distXZ)
        val yawRaw = if (distXZ > 1e-3f) Math.toDegrees((-atan2(dx, dz)).toDouble()).toFloat() else yawAxis.angle
        val pitchRaw = (-Math.toDegrees(atan2(dy, max(distXZ, PITCH_MIN_DIST)).toDouble())).toFloat().coerceIn(-90f, 90f)

        val errYaw = wrap180(yawRaw - yawAxis.angle) * nearBlend
        val errPitch = pitchRaw - pitchAxis.angle

        // ---- zona muerta ~ fraccion del hitbox visto desde aqui ----
        val distRef = max(distXZ, 0.5f)
        val dist3 = max(hypot(distXZ, dy), 0.5f)
        val dzYaw = Math.toDegrees(atan(width * DEADZONE_W / distRef).toDouble()).toFloat().coerceIn(0.15f, 4f)
        val dzPitch = Math.toDegrees(atan(height * DEADZONE_H / dist3).toDouble()).toFloat().coerceIn(0.15f, 3f)

        // ---- ganancia: mas agresiva cuando el objetivo esta muy fuera de la vista (p.ej. detras) ----
        val boostYaw = smoothstep(30f, 120f, abs(errYaw))
        val boostPitch = smoothstep(20f, 70f, abs(errPitch))
        val kYaw = cfg.smoothing + (1f - cfg.smoothing) * 0.5f * boostYaw
        val kPitch = cfg.smoothing + (1f - cfg.smoothing) * 0.5f * boostPitch

        val vMaxYaw = cfg.maxYawSpeed
        val aMaxYaw = cfg.turnAccel
        val stepYaw = advance(yawAxis, errYaw, kYaw.coerceIn(0.01f, 1f), vMaxYaw, aMaxYaw, dzYaw, dt)
        val stepPitch = advance(pitchAxis, errPitch, kPitch.coerceIn(0.01f, 1f), vMaxYaw * PITCH_RATIO, aMaxYaw * PITCH_RATIO, dzPitch, dt)

        yawAxis.angle = wrap180(yawAxis.angle + stepYaw)
        pitchAxis.angle = (pitchAxis.angle + stepPitch).coerceIn(-90f, 90f)
    }

    /**
     * Sin objetivo: vuelve suavemente a la rotacion real de la camara y, cuando la
     * alcanza, deja de sobreescribirla (isOverriding = false).
     */
    fun release(realYaw: Float, realPitch: Float, tick: Long) {
        if (!isOverriding) return
        val cfg = config
        val dt = consumeDt(tick)
        if (dt == 0) return

        val errYaw = wrap180(realYaw - yawAxis.angle)
        val errPitch = realPitch.coerceIn(-90f, 90f) - pitchAxis.angle
        val stepYaw = advance(yawAxis, errYaw, cfg.smoothing.coerceIn(0.01f, 1f), cfg.maxYawSpeed, cfg.turnAccel, 0.25f, dt)
        val stepPitch = advance(pitchAxis, errPitch, cfg.smoothing.coerceIn(0.01f, 1f), cfg.maxYawSpeed * PITCH_RATIO, cfg.turnAccel * PITCH_RATIO, 0.25f, dt)
        yawAxis.angle = wrap180(yawAxis.angle + stepYaw)
        pitchAxis.angle = (pitchAxis.angle + stepPitch).coerceIn(-90f, 90f)

        val nearYaw = abs(wrap180(realYaw - yawAxis.angle)) < RELEASE_EPS
        val nearPitch = abs(realPitch.coerceIn(-90f, 90f) - pitchAxis.angle) < RELEASE_EPS
        if (nearYaw && nearPitch) {
            isOverriding = false
            yawAxis.vel = 0f
            pitchAxis.vel = 0f
        }
    }

    // =====================================================================
    // Internos
    // =====================================================================

    /** Ticks transcurridos desde el ultimo paso (1..MAX_DT), 0 si es el mismo tick. */
    private fun consumeDt(tick: Long): Int {
        if (tick < lastStepTick) lastStepTick = tick - 1
        if (tick == lastStepTick) return 0
        val dt = if (lastStepTick == Long.MIN_VALUE) 1 else (tick - lastStepTick).coerceIn(1L, MAX_DT.toLong()).toInt()
        lastStepTick = tick
        return dt
    }

    /**
     * Un eje: la velocidad angular sigue a una velocidad deseada (proporcional al
     * error, topada por vMax y por la distancia de frenado) con cambio de
     * velocidad limitado por aMax. Devuelve el desplazamiento en grados.
     */
    private fun advance(axis: Axis, err: Float, k: Float, vMax: Float, aMax: Float, deadzone: Float, dt: Int): Float {
        val a = abs(err)
        val eEff = if (a <= deadzone) 0f else sign(err) * (a - deadzone)

        val alpha = 1f - (1f - k).pow(dt)
        var vDes = eEff * alpha / dt
        val cap = min(vMax, sqrt(2f * aMax * abs(eEff)))
        vDes = vDes.coerceIn(-cap, cap)

        val dv = (vDes - axis.vel).coerceIn(-aMax * dt, aMax * dt)
        axis.vel += dv

        var stepDeg = axis.vel * dt
        // no pasarse del objetivo en el ultimo paso (evita oscilar alrededor del error 0)
        if (eEff != 0f && stepDeg * eEff > 0f && abs(stepDeg) > abs(eEff)) {
            stepDeg = eEff
            axis.vel = stepDeg / dt
        }
        return stepDeg
    }

    private fun wrap180(angle: Float): Float {
        var d = angle % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 <= edge0) return if (x >= edge1) 1f else 0f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private companion object {
        // tracker
        const val HOLD_TICKS = 2L
        const val IDLE_DECAY = 0.7f
        const val TELEPORT_DIST = 6f
        const val TURN_COS = 0.5f          // > 60 grados de cambio de direccion horizontal
        const val BRAKE_RATIO = 0.4f       // frenada brusca: velocidad cruda < 40% de la estimada
        const val VEL_ALPHA = 0.4f
        const val FAST_ALPHA = 0.7f
        const val AY_ALPHA = 0.3f
        const val TURN_CONF = 0.2f
        const val BRAKE_CONF = 0.35f
        const val CONF_RECOVER = 0.2f

        // adelanto
        const val LEAD_ALPHA = 0.4f
        const val VY_MIN = 0.04f
        const val GRAVITY = 0.09f
        const val MAX_LEAD_UP = 1.5f
        const val MAX_LEAD_DOWN = 1.2f

        // angulos
        const val NEAR_MIN = 0.2f
        const val NEAR_MAX = 0.8f
        const val PITCH_MIN_DIST = 0.3f
        const val DEADZONE_W = 0.15f
        const val DEADZONE_H = 0.10f
        const val PITCH_RATIO = 0.6f
        const val MAX_DT = 2
        const val RELEASE_EPS = 1.0f
    }
}
