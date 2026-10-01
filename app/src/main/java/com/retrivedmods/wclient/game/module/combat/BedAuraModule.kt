package com.retrivedmods.wclient.game.module.combat

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.entity.Player
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

/**
 * Igual que Anchor Aura pero con camas: las 16 variantes de color acaban en
 * "_bed" (p.ej. minecraft:red_bed) - se detecta por sufijo en vez de un
 * identifier fijo para cubrir cualquier color sin listarlos todos.
 */
class BedAuraModule : Module("Bed Aura", ModuleCategory.Combat) {

    private var range by floatValue("Range", 5f, 2f..8f)
    private var interval by intValue("Interval", 1, 1..20)
    private var triggerDelayTicks by intValue("Trigger Delay", 1, 1..10)

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
            placementKey = target.runtimeEntityId
        )
    }

    override fun onDisabled() {
        super.onDisabled()
        // placer es "by lazy": si el modulo se apaga ANTES de haberse
        // conectado a un server (session sin inicializar todavia), tocar
        // placer aqui lo crearia por primera vez y necesita session -> 
        // UninitializedPropertyAccessException. El Switch del ClickGUI llama
        // a esto directo, sin el try/catch que ya protege beforePacketBound.
        if (isSessionCreated) placer.reset()
    }

    override fun onDisconnect(reason: String) {
        placer.reset()
    }
}
