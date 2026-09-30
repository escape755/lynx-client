package com.retrivedmods.wclient.game.module.combat

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.entity.Player
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/**
 * Version "helper" de Anchor Aura: NO cambia tu slot por ti. Solo coloca y
 * activa cuando ya tienes un ancla de respawn seleccionada a mano - asiste
 * un intento manual en vez de automatizarlo por completo (misma distincion
 * que Aura/Helper en el resto del cliente: Aura decide sola, Helper asiste
 * lo que ya estas haciendo).
 */
class AnchorHelperModule : Module("Anchor Helper", ModuleCategory.Combat) {

    private var range by floatValue("Range", 5f, 2f..8f)
    private var interval by intValue("Interval", 1, 1..20)
    private var triggerDelayTicks by intValue("Trigger Delay", 1, 1..10)

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
            requireManualHold = true,
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
