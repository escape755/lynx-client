package com.retrivedmods.wclient.game.module.combat

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerHotbarPacket
import kotlin.math.floor

/**
 * Coloca el bloque configurado en los 4 lados (N/S/E/W) al nivel de los
 * pies del jugador local, mas uno directamente debajo (cubre el hueco bajo
 * tus pies - p.ej. contra perlas/caidas). Este patron de 5 posiciones esta
 * confirmado por DOS referencias independientes que me pasaron (Surround.cpp
 * de Veyra, y el sidesBlocksOnly[5] de la otra libreria de modulos) - antes
 * solo tenia las 4 laterales.
 *
 * No usa ExplosiveBlockPlacer: esa clase modela "un objetivo, colocar,
 * esperar, activar" (Anchor/Bed); Surround son posiciones fijas relativas
 * a uno mismo, de una sola fase, sin nada que activar - una forma distinta
 * del problema, asi que tiene su propio envio (mismo patron de paquete ya
 * verificado, sin forzar una abstraccion que no encaja).
 *
 * Misma limitacion que Anchor/Bed: no hay tracking del mundo, así que se
 * reintentan las 5 posiciones cada intervalo sin saber cuales ya estan
 * puestas - las que ya estan ocupadas simplemente no cambian nada en el
 * servidor.
 */
class SurroundModule : Module("Surround", ModuleCategory.Combat) {

    private var blockIdentifier by stringValue("Block", "minecraft:obsidian", null)
    private var interval by intValue("Interval", 1, 1..20)

    // dx, dy, dz del BLOQUE DE REFERENCIA (la cara UP de ese bloque es
    // donde aparece el nuevo bloque). Los 4 laterales referencian el bloque
    // bajo cada lado (resultado: aparece al nivel de mis pies, al lado);
    // el ultimo referencia dos bloques abajo (resultado: aparece justo
    // debajo de mis pies).
    private val offsets = arrayOf(
        Triple(1, -1, 0), Triple(-1, -1, 0), Triple(0, -1, 1), Triple(0, -1, -1),
        Triple(0, -2, 0)
    )

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet
        if (packet !is PlayerAuthInputPacket) return
        if (packet.tick % interval != 0L) return

        val inv = session.localPlayer.inventory
        val slot = inv.searchForItemInHotbar {
            it != ItemData.AIR && it.definition?.identifier == blockIdentifier
        } ?: return

        if (inv.heldItemSlot != slot) {
            session.serverBound(PlayerHotbarPacket().apply {
                selectedHotbarSlot = slot
                containerId = 0
                selectHotbarSlot = true
            })
        }

        val item = inv.content[slot]
        val baseX = floor(session.localPlayer.posX).toInt()
        val baseY = floor(session.localPlayer.posY).toInt()
        val baseZ = floor(session.localPlayer.posZ).toInt()

        for ((dx, dy, dz) in offsets) {
            val packetUse = InventoryTransactionPacket().apply {
                transactionType = InventoryTransactionType.ITEM_USE
                actionType = 0
                blockPosition = Vector3i.from(baseX + dx, baseY + dy, baseZ + dz)
                blockFace = 1 // UP
                hotbarSlot = slot
                itemInHand = item
                playerPosition = session.localPlayer.vec3Position
                clickPosition = Vector3f.from(0.5f, 1f, 0.5f)
            }
            session.serverBound(packetUse)
        }
    }
}
