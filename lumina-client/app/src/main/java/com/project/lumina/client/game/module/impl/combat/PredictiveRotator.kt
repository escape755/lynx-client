/*
 * © Project Lumina 2026 — GPLv3 Licensed
 * You may use, modify, and share this code under the GPL.
 *
 * Just know: changing names and colors doesn't make you a developer.
 * Think before you fork. Build something real — or don't bother.
 */

package com.project.lumina.client.game.module.impl.combat

import com.project.lumina.client.constructors.NetBound
import com.project.lumina.client.game.entity.Entity
import com.project.lumina.client.game.entity.Player
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.entity.EntityDataTypes
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import kotlin.math.abs

/**
 * Pegamento entre KillauraElement y el motor [PredictiveAim]. Port directo
 * de PredictiveRotator.kt (LynxClient) - no dependia de nada propio de ese
 * proyecto (ni Module, ni ModuleValues), asi que el unico cambio real es
 * GameSession -> NetBound.
 */
class PredictiveRotator {

    class Settings(
        val predTime: Float,
        val predStrength: Float,
        val smoothing: Float,
        val maxSpeed: Float,
        val accel: Float,
        val cameraSync: Boolean,
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

    fun update(
        session: NetBound,
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
                aim.release(aim.yaw, aim.pitch, tick)
            } else {
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

    private fun syncCamera(session: NetBound, packet: PlayerAuthInputPacket, s: Settings) {
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
        const val LOCK_SWITCH_RATIO = 0.75f
        const val LOCK_SWITCH_MIN_GAIN = 0.75f
        const val LOCK_MIN_HOLD_TICKS = 6L
    }
}
