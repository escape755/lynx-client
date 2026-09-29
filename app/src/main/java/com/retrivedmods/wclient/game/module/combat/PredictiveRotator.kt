package com.retrivedmods.wclient.game.module.combat

import com.retrivedmods.wclient.game.GameSession
import com.retrivedmods.wclient.game.entity.Entity
import com.retrivedmods.wclient.game.entity.Player
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.abs

/**
 * Pegamento entre un modulo Aura y el motor [PredictiveAim]. Lo comparten LynxAura,
 * LynxAuraX y LynxAuraPro para que el modo "Predictive" se comporte igual en las tres.
 *
 * Por tick (PlayerAuthInputPacket) hace, en este orden:
 *  1. alimenta el tracker de movimiento con TODOS los candidatos (asi un objetivo nuevo ya
 *     tiene velocidad estimada cuando se le apunta por primera vez);
 *  2. elige objetivo con histeresis (no salta entre jugadores a distancias casi iguales) y lo
 *     deja en targets[0], que es el que ataca el modulo en modo Single;
 *  3. convierte posiciones a una misma referencia (pies del objetivo, ojos del jugador local);
 *  4. avanza el motor un paso y, si [Settings.cameraSync], gira tambien la camara visible.
 *
 * El modulo solo tiene que llamar a [update] en cada tick, copiar [pitch]/[yaw] al
 * PlayerAuthInputPacket mientras [isOverriding] sea true, y llamar a [reset] al
 * activarse/desactivarse.
 */
class PredictiveRotator {

    class Settings(
        val predTime: Float,
        val predStrength: Float,
        val smoothing: Float,
        val maxSpeed: Float,
        val accel: Float,
        val cameraSync: Boolean,
        /** Se resta a la Y de un Player (que llega a nivel de ojos) para obtener sus pies. */
        val playerYOffset: Float,
        val vertOffset: Float
    )

    private val aim = PredictiveAim()
    private var lockedId = -1L
    private var lockedSinceTick = 0L
    private var lastSyncYaw = Float.NaN
    private var lastSyncPitch = Float.NaN

    var pitch = 0f
        private set
    var yaw = 0f
        private set

    /** true mientras el modulo debe sobreescribir la rotacion del cliente con [pitch]/[yaw]. */
    val isOverriding: Boolean get() = aim.isOverriding

    fun reset() {
        aim.reset()
        lockedId = -1L
        lockedSinceTick = 0L
        lastSyncYaw = Float.NaN
        lastSyncPitch = Float.NaN
        pitch = 0f
        yaw = 0f
    }

    /**
     * @param targets candidatos ordenados por prioridad (targets[0] = preferido). Se reordena
     * in situ para que el objetivo bloqueado quede en el indice 0.
     * @param hysteresis false para respetar estrictamente el orden del modulo (p. ej. prioridad
     * por salud): entonces siempre se apunta a targets[0].
     * @return true si el modulo debe sobreescribir la rotacion en este tick.
     */
    fun update(
        session: GameSession,
        packet: PlayerAuthInputPacket,
        targets: MutableList<Entity>,
        s: Settings,
        hysteresis: Boolean = true
    ): Boolean {
        val localPlayer = session.localPlayer
        val tick = packet.tick
        aim.config = PredictiveAim.Config(
            predictionTicks = s.predTime,
            predictionStrength = s.predStrength,
            smoothing = s.smoothing,
            maxYawSpeed = s.maxSpeed,
            turnAccel = s.accel
        )

        for (e in targets) aim.observe(e.runtimeEntityId, e.posX, feetY(e, s), e.posZ, tick)
        aim.retain(targets.map { it.runtimeEntityId })

        if (targets.isEmpty()) {
            lockedId = -1L
            if (!aim.isOverriding) return false
            if (s.cameraSync) {
                // La camara ya esta donde la dejamos: solo se deja de girar con frenado suave.
                aim.release(aim.yaw, aim.pitch, tick)
            } else {
                // Sin camara sincronizada: volver suavemente a la rotacion real del cliente.
                aim.release(packet.rotation.y, packet.rotation.x, tick)
            }
            publish()
            syncCamera(session, packet, s)
            return aim.isOverriding
        }

        if (hysteresis) applyTargetLock(targets, localPlayer.posX, localPlayer.posY, localPlayer.posZ, tick)
        val handler = targets[0]
        if (handler.runtimeEntityId != lockedId) {
            lockedId = handler.runtimeEntityId
            lockedSinceTick = tick
        }

        // Al adquirir objetivo se parte de la rotacion real de la camara (sin salto inicial).
        if (!aim.isOverriding) aim.seed(packet.rotation.y, packet.rotation.x, tick)

        val (w, h) = dims(handler)
        val eyeY = localPlayer.posY - s.playerYOffset + EYE_HEIGHT
        aim.step(
            localPlayer.posX, eyeY, localPlayer.posZ,
            handler.runtimeEntityId,
            handler.posX, feetY(handler, s), handler.posZ,
            w, h,
            s.vertOffset,
            tick
        )
        publish()
        syncCamera(session, packet, s)
        return true
    }

    private fun publish() {
        pitch = aim.pitch
        yaw = aim.yaw
    }

    private fun feetY(e: Entity, s: Settings): Float =
        if (e is Player) e.posY - s.playerYOffset else e.posY

    private fun dims(e: Entity): Pair<Float, Float> {
        val w = e.metadata[EntityDataTypes.WIDTH] as? Float ?: 0.6f
        val h = e.metadata[EntityDataTypes.HEIGHT] as? Float ?: 1.8f
        return w to h
    }

    /**
     * Mantiene el objetivo actual salvo que otro este claramente mas cerca y el actual lleve
     * un rato bloqueado. Evita que la mira salte entre jugadores cuyas distancias son casi
     * iguales. Deja el elegido en targets[0].
     */
    private fun applyTargetLock(targets: MutableList<Entity>, lx: Float, ly: Float, lz: Float, tick: Long) {
        val nearest = targets[0]
        val current = if (lockedId == -1L) null else targets.firstOrNull { it.runtimeEntityId == lockedId }

        val chosen = when {
            current == null -> nearest
            nearest === current -> current
            else -> {
                val dCur = current.distance(lx, ly, lz)
                val dNear = nearest.distance(lx, ly, lz)
                val clearlyCloser = dNear < dCur * LOCK_SWITCH_RATIO && dCur - dNear > LOCK_SWITCH_MIN_GAIN
                if (clearlyCloser && tick - lockedSinceTick >= LOCK_MIN_HOLD_TICKS) nearest else current
            }
        }

        if (chosen !== targets[0]) {
            targets.remove(chosen)
            targets.add(0, chosen)
        }
    }

    /**
     * Gira la camara visible del cliente hacia (pitch, yaw). La rotacion que va al servidor la
     * lleva el PlayerAuthInputPacket; esto es lo que la hace visible. Mismo mecanismo que ya
     * usan PlayerTP / Replay: MovePlayerPacket (Mode.NORMAL) hacia el cliente sobre el jugador
     * local, con rotation = (pitch, yaw, headYaw).
     *
     * La posicion enviada es la del ultimo PlayerAuthInputPacket + un tick de movimiento, para
     * que si el cliente la aplica no lo arrastre hacia atras. Solo se envia cuando la rotacion
     * cambio de verdad.
     */
    private fun syncCamera(session: GameSession, packet: PlayerAuthInputPacket, s: Settings) {
        if (!s.cameraSync) return
        if (!lastSyncYaw.isNaN()) {
            var dYaw = yaw - lastSyncYaw
            while (dYaw > 180f) dYaw -= 360f
            while (dYaw < -180f) dYaw += 360f
            if (abs(dYaw) < SYNC_EPS && abs(pitch - lastSyncPitch) < SYNC_EPS) return
        }
        lastSyncYaw = yaw
        lastSyncPitch = pitch

        val localPlayer = session.localPlayer
        session.clientBound(MovePlayerPacket().apply {
            runtimeEntityId = localPlayer.runtimeEntityId
            position = Vector3f.from(
                packet.position.x + localPlayer.motionX,
                packet.position.y + localPlayer.motionY,
                packet.position.z + localPlayer.motionZ
            )
            rotation = Vector3f.from(pitch, yaw, yaw)
            mode = MovePlayerPacket.Mode.NORMAL
            onGround = packet.inputData.contains(PlayerAuthInputData.VERTICAL_COLLISION) && localPlayer.motionY <= 0f
            ridingRuntimeEntityId = 0
            tick = packet.tick
        })
    }

    private companion object {
        const val EYE_HEIGHT = 1.62f
        const val SYNC_EPS = 0.01f

        // Cambio de objetivo: solo si el nuevo esta >25% mas cerca y al menos 0.75 bloques
        // mas cerca, y el actual lleva al menos 6 ticks bloqueado.
        const val LOCK_SWITCH_RATIO = 0.75f
        const val LOCK_SWITCH_MIN_GAIN = 0.75f
        const val LOCK_MIN_HOLD_TICKS = 6L
    }
}
