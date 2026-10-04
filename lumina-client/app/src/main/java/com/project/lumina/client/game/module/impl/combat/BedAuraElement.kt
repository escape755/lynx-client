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
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.game.entity.Player
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/**
 * No existia en Lumina (si en MuCuteClient, con tracking de bloques real
 * que aqui no hay - ver ExplosiveBlockPlacer). Igual que AnchorAura pero
 * con camas: las 16 variantes de color acaban en "_bed", se detecta por
 * sufijo para cubrir cualquier color sin listarlos todos.
 */
class BedAuraElement : Element(
    name = "BedAura",
    category = CheatCategory.Combat
) {

    private var range by floatValue("Range", 5f, 2f..8f)
    private var interval by intValue("Interval", 1, 1..20)
    private var triggerDelayTicks by intValue("Trigger Delay", 1, 1..10)
    private var selfDamageCap by floatValue("Self Damage Cap", 18f, 2f..36f)

    private val placer by lazy {
        ExplosiveBlockPlacer(session) { it.definition?.identifier?.endsWith("_bed") == true }
    }

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
            placementKey = target.runtimeEntityId,
            selfDamageCap = selfDamageCap
        )
    }

    override fun onDisabled() {
        super.onDisabled()
        if (isSessionCreated) placer.reset()
    }
}
