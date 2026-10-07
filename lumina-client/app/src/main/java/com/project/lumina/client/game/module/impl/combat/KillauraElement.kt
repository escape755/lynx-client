/*
 * © Project Lumina 2026 — GPLv3 Licensed
 * You may use, modify, and share this code under the GPL.
 *
 * Just know: changing names and colors doesn't make you a developer.
 * Think before you fork. Build something real — or don't bother.
 */

package com.project.lumina.client.game.module.impl.combat

import com.project.lumina.client.constructors.Element
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.constructors.ListItem
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.game.entity.Entity
import com.project.lumina.client.game.entity.Player
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/**
 * Killaura de Lumina. Idea de targeting tomada de KillauraModule.java
 * (LeHu/KiwiMods, fuente decompilada) - busqueda por rango, CPS, isBot -
 * pero NO sus 12 modos de rotacion (SNAP/SMOOTH/LEGIT/JITTER/STRAFE/etc):
 * por instruccion explicita, las unicas dos rotaciones de este proyecto son
 * Unified (UnifiedRotation.kt) y Predictive (PredictiveAim/PredictiveRotator),
 * ambas ya construidas y afinadas para LynxClient, portadas aqui.
 *
 * Mejora tomada de LeHu: isBot() revisa tambien xuid vacio/"0", no solo
 * nombre en blanco - mas fiable que la version anterior (solo nombre) que
 * se uso en LynxAuraModule/CrystalAuraElement.
 *
 * No se porto el chequeo de FriendManager de LeHu: Lumina no tiene sistema
 * de amigos (mismo motivo por el que PopCounterElement tampoco lo tiene).
 */
class KillauraElement : Element(
    name = "Killaura",
    category = CheatCategory.Combat
) {

    private enum class RotMode(override val name: String) : ListItem {
        Unified("Unified"),
        Predictive("Predictive")
    }

    private var range by floatValue("Range", 4.2f, 2f..7f)
    private var cpsValue by intValue("CPS", 10, 1..20)
    private var packets by intValue("Packets", 1, 1..10)
    private var playersOnly by boolValue("Players Only", true)
    private var antiBot by boolValue("Anti Bot", true)
    private var rotModeItem by listValue("Rotation", RotMode.Unified, setOf(RotMode.Unified, RotMode.Predictive))

    // Unified
    private var predTime by floatValue("Pred Time", 2.0f, 0.5f..4f)
    private var predStrength by floatValue("Pred Strength", 1.0f, 0f..2f)
    private var rotSmoothing by floatValue("Rot Smoothing", 0.35f, 0.05f..1f)
    private var rotMaxSpeed by floatValue("Rot Max Speed", 30f, 5f..60f)
    private var rotAccel by floatValue("Rot Accel", 12f, 1f..30f)
    private var cameraSync by boolValue("Camera Sync", true)

    private var lastAttackTime = 0L
    private val unifiedCtx = UnifiedRotation.Ctx()
    private val predictive = PredictiveRotator()

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet
        if (packet !is PlayerAuthInputPacket) return

        val targets = findTargets()
        if (targets.isEmpty()) {
            if (predictive.isOverriding) {
                predictive.update(session, packet, mutableListOf(), predictiveSettings())
            }
            return
        }

        when (rotModeItem) {
            RotMode.Unified -> applyUnified(packet, targets[0])
            RotMode.Predictive -> {
                val mutable = targets.toMutableList()
                if (predictive.update(session, packet, mutable, predictiveSettings())) {
                    packet.rotation = org.cloudburstmc.math.vector.Vector3f.from(
                        predictive.pitch, predictive.yaw, predictive.yaw
                    )
                }
            }
        }

        val now = System.currentTimeMillis()
        val minDelay = 1000L / cpsValue
        if (now - lastAttackTime < minDelay) return

        targets.forEach { target ->
            repeat(packets) { session.localPlayer.attack(target) }
        }
        lastAttackTime = now
    }

    private fun applyUnified(packet: PlayerAuthInputPacket, target: Entity) {
        val localPlayer = session.localPlayer
        val eyeY = localPlayer.posY
        UnifiedRotation.unified(
            unifiedCtx,
            UnifiedRotation.Target(
                target.posX, feetYOf(target), target.posZ,
                target.motionX, target.motionY, target.motionZ,
                1.8f
            ),
            UnifiedRotation.Env(
                localPlayer.posX, eyeY, localPlayer.posZ,
                localPlayer.motionX, localPlayer.motionY, localPlayer.motionZ
            )
        )
        packet.rotation = org.cloudburstmc.math.vector.Vector3f.from(
            unifiedCtx.rotPitch, unifiedCtx.rotYaw, unifiedCtx.headYaw
        )
    }

    private fun predictiveSettings() = PredictiveRotator.Settings(
        predTime = predTime,
        predStrength = predStrength,
        smoothing = rotSmoothing,
        maxSpeed = rotMaxSpeed,
        accel = rotAccel,
        cameraSync = cameraSync,
        playerYOffset = 1.62f,
        vertOffset = 0.3f
    )

    private fun feetYOf(e: Entity): Float = if (e is Player) e.posY - 1.62f else e.posY

    private fun findTargets(): List<Entity> {
        val localPlayer = session.localPlayer
        return session.level.entityMap.values
            .filter { entity ->
                entity.distance(localPlayer) <= range &&
                        isTarget(entity)
            }
            .sortedBy { it.distance(localPlayer) }
    }

    private fun isTarget(entity: Entity): Boolean {
        if (entity.runtimeEntityId == session.localPlayer.runtimeEntityId) return false
        val isPlayer = entity is Player
        if (playersOnly && !isPlayer) return false
        if (isPlayer && antiBot && isBot(entity as Player)) return false
        return true
    }

    /** isBot: revisa tanto el nombre como el xuid en playerMap - idea de LeHu. */
    private fun isBot(player: Player): Boolean {
        val entry = session.level.playerMap[player.uuid] ?: return true
        if (entry.name.isBlank()) return true
        val xuid = entry.xuid
        return xuid.isNullOrBlank() || xuid == "0"
    }

    override fun onDisabled() {
        super.onDisabled()
        if (isSessionCreated) predictive.reset()
    }
}
