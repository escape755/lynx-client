package com.retrivedmods.wclient.game.module.visual

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.entity.Entity
import com.retrivedmods.wclient.game.entity.EntityUnknown
import com.retrivedmods.wclient.game.entity.LocalPlayer
import com.retrivedmods.wclient.game.entity.MobList
import com.retrivedmods.wclient.game.entity.Player
import com.retrivedmods.wclient.overlay.hud.TargetHudOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

class TargetHudModule : Module("targethud", ModuleCategory.Visual) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val playerOnly by boolValue("Player Only", true)
    private val mobsOnly by boolValue("Mobs Only", false)
    private val rangeValue by floatValue("Range", 20f, 1f..20f)
    private val maxDistance by floatValue("Max Distance", 50f, 10f..100f)
    private val positionX by intValue("Position X", 0, -500..500)
    private val positionY by intValue("Position Y", -200, -500..500)
    private val scale by floatValue("Scale", 1.0f, 0.5f..2.0f)
    private val showDistance by boolValue("Show Distance", true)
    private val showStatus by boolValue("Show Status", true)
    private val backgroundOpacity by floatValue("Background Opacity", 0.6f, 0.0f..1.0f)

    override fun onEnabled() {
        super.onEnabled()
        scope.launch {
            updateOverlaySettings()
        }
    }

    private fun updateOverlaySettings() {
        try {
            TargetHudOverlay.setPosition(positionX, positionY)
            TargetHudOverlay.setScale(scale)
            TargetHudOverlay.setShowDistance(showDistance)
            TargetHudOverlay.setShowStatus(showStatus)
            TargetHudOverlay.setBackgroundOpacity(backgroundOpacity)
        } catch (_: Exception) {
        }
    }

    override fun onDisabled() {
        super.onDisabled()
        hudShown = false
        if (isSessionCreated) {
            scope.launch {
                try {
                    TargetHudOverlay.dismissTargetHud()
                } catch (_: Exception) {
                }
            }
        }
    }

    private var lastUpdateTime = 0L
    private val updateIntervalMs = 150L

    // true mientras la ventana del HUD está (o se pidió que esté) visible.
    // Se toca desde los dos hilos de red, de ahí el @Volatile.
    @Volatile
    private var hudShown = false

    /**
     * Pide ocultar el HUD SOLO si estaba visible. Antes, con el módulo apagado
     * (el estado por defecto), beforePacketBound lanzaba una corrutina en el
     * hilo principal (Dispatchers.Main) con dismissTargetHud() por CADA paquete
     * de la conexión, en ambos sentidos. Cada una acababa en
     * WindowManager.removeView() sobre una vista que no estaba adjunta, que
     * lanza (y se traga) una IllegalArgumentException con su stack trace. El
     * número de paquetes crece con la cantidad de jugadores/entidades cerca, así
     * que el hilo de UI (overlays, ClickGUI) se saturaba justo en esas escenas.
     */
    private fun hideHudIfShown() {
        if (!hudShown) return
        hudShown = false
        scope.launch {
            try {
                TargetHudOverlay.dismissTargetHud()
            } catch (_: Exception) {
            }
        }
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled || !isSessionCreated) {
            hideHudIfShown()
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastUpdateTime < updateIntervalMs) return
        lastUpdateTime = now

        val closestEntity = findClosestTarget()

        if (closestEntity != null) {
            val username = getEntityName(closestEntity)
            val distance = closestEntity.distance(session.localPlayer)
            val skinData = if (closestEntity is Player) {
                session.level.playerMap[closestEntity.uuid]?.skin
            } else null

            hudShown = true
            scope.launch {
                try {
                    TargetHudOverlay.showTargetHud(username, skinData, distance, maxDistance, 0f)
                } catch (_: Exception) {
                }
            }
        } else {
            hideHudIfShown()
        }
    }

    private fun Entity.isTarget(): Boolean {
        return when (this) {
            is LocalPlayer -> false
            is Player -> {
                if (playerOnly) {
                    !this.isBot()
                } else if (!playerOnly && !mobsOnly) {
                    !this.isBot()
                } else {
                    false
                }
            }
            is EntityUnknown -> {
                if (mobsOnly) {
                    isMob()
                } else if (!playerOnly && !mobsOnly) {
                    isMob()
                } else {
                    false
                }
            }
            else -> false
        }
    }

    private fun EntityUnknown.isMob(): Boolean {
        return this.identifier in MobList.mobTypes
    }

    private fun Player.isBot(): Boolean {
        if (this is LocalPlayer) {
            return false
        }
        val playerData = session.level.playerMap[this.uuid]
        return (playerData?.name?.toString() ?: "").isBlank()
    }

    private fun findClosestTarget(): Entity? {
        return session.level.entityMap.values
            .asSequence()
            .filter { it.isTarget() }
            .map { it to it.distance(session.localPlayer) }
            .filter { (_, distance) -> distance < rangeValue }
            .minByOrNull { it.second }
            ?.first
    }

    private fun getEntityName(entity: Entity): String {
        return when (entity) {
            is Player -> session.level.playerMap[entity.uuid]?.name?.toString()?.takeIf { it.isNotBlank() } ?: "Player"
            is EntityUnknown -> entity.identifier.split(":").lastOrNull()?.replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            } ?: "Unknown"
            else -> entity.javaClass.simpleName
        }
    }
}