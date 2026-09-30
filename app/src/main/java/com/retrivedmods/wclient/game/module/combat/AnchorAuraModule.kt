package com.retrivedmods.wclient.game.module.combat

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.entity.Player
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/**
 * Coloca un ancla de respawn junto al jugador enemigo mas cercano y la
 * activa - automatico, cambia de slot el solo. Requiere anclas YA cargadas
 * en el inventario (cargarlas es una accion manual de preparacion, este
 * modulo no gestiona eso). Ver ExplosiveBlockPlacer para la mecanica de
 * colocacion/activacion y su limitacion conocida (no hay tracking del mundo,
 * la posicion de colocacion se asume).
 */
class AnchorAuraModule : Module("Anchor Aura", ModuleCategory.Combat) {

    private var range by floatValue("Range", 5f, 2f..8f)
    private var interval by intValue("Interval", 4, 1..20)
    private var triggerDelayTicks by intValue("Trigger Delay", 3, 1..10)

    private val placer by lazy { ExplosiveBlockPlacer(session) { it.definition?.identifier == "minecraft:respawn_anchor" } }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet
        if (packet !is PlayerAuthInputPacket) return
        if (packet.tick % interval != 0L) return

        val localPlayer = session.localPlayer
        val target = session.level.entityMap.values
            .filter { it is Player && it.distance(localPlayer) <= range }
            .minByOrNull { it.distance(localPlayer) } ?: return

        placer.tryPlaceAndTrigger(
            target.posX, target.posY, target.posZ,
            packet.tick, triggerDelayTicks.toLong(),
            requireManualHold = false,
            placementKey = target.runtimeEntityId
        )
    }

    override fun onDisabled() {
        super.onDisabled()
        placer.reset()
    }

    override fun onDisconnect(reason: String) {
        placer.reset()
    }
}
