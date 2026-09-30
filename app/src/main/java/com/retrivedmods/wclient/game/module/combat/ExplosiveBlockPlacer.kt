package com.retrivedmods.wclient.game.module.combat

import com.retrivedmods.wclient.game.GameSession
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerHotbarPacket
import kotlin.math.floor

/**
 * Mecánica compartida por Anchor Aura, Anchor Helper y Bed Aura: buscar un
 * item que hace explotar el bloque al usarse en la dimensión equivocada
 * (ancla de respawn o cama), colocarlo junto a un objetivo y activarlo.
 *
 * NO es un Module - lo instancia cada módulo (mismo patrón que
 * PredictiveRotator para las 3 Aura), cada uno con su propio identifier,
 * settings y lista de objetivos/gating.
 *
 * LIMITACIÓN CONOCIDA (ver análisis): Level no trackea el estado de los
 * bloques del mundo. blockPosition se asume (el bloque bajo los pies de
 * referencia); si esa posición no es sólida en el servidor real, este
 * mismo mecanismo hace que el servidor simplemente descarte la colocación
 * - no hay excepción ni corrupción de estado, solo un intento sin efecto.
 * No se intenta adivinar ni trackear el mundo para evitarlo.
 */
class ExplosiveBlockPlacer(
    private val session: GameSession,
    private val isTargetItem: (ItemData) -> Boolean
) {

    // runtimeEntityId del objetivo -> tick en el que se coloco, para saber
    // cuando toca el paso de activacion. Solo vive mientras la secuencia
    // esta en curso; entradas resueltas (o abandonadas) se limpian solas.
    private val placedAt = HashMap<Long, Long>()

    fun reset() {
        placedAt.clear()
    }

    /**
     * @param refX/Y/Z posicion de referencia (pies del objetivo, o del jugador local para Surround)
     * @param currentTick tick actual (PlayerAuthInputPacket.tick de este mismo paquete)
     * @param triggerAfterTicks cuantos ticks esperar entre colocar y activar
     * @param requireManualHold si es true, solo actua cuando el hotbar YA tiene el item
     *        seleccionado a mano (Anchor Helper); si es false, cambia de slot el solo (Aura)
     * @param placementKey clave estable para esta secuencia colocar->activar
     *        (p.ej. runtimeEntityId del objetivo, o una constante fija para Surround)
     * @return true si mando algun paquete este tick
     */
    fun tryPlaceAndTrigger(
        refX: Float,
        refY: Float,
        refZ: Float,
        currentTick: Long,
        triggerAfterTicks: Long,
        requireManualHold: Boolean,
        placementKey: Long
    ): Boolean {
        val inv = session.localPlayer.inventory

        val slot = inv.searchForItemInHotbar { isTargetItem(it) } ?: run {
            placedAt.remove(placementKey)
            return false
        }

        val phase = placedAt[placementKey]
        if (phase != null) {
            if (currentTick - phase < triggerAfterTicks) return false
            placedAt.remove(placementKey)
            return sendUse(slot, inv.content[slot], refX, refY, refZ, atGroundLevel = false)
        }

        if (requireManualHold && inv.heldItemSlot != slot) return false

        if (!requireManualHold && inv.heldItemSlot != slot) {
            session.serverBound(PlayerHotbarPacket().apply {
                selectedHotbarSlot = slot
                containerId = 0
                selectHotbarSlot = true
            })
        }

        val sent = sendUse(slot, inv.content[slot], refX, refY, refZ, atGroundLevel = true)
        if (sent) placedAt[placementKey] = currentTick
        return sent
    }

    private fun sendUse(
        slot: Int,
        item: ItemData,
        refX: Float,
        refY: Float,
        refZ: Float,
        atGroundLevel: Boolean
    ): Boolean {
        if (item == ItemData.AIR) return false

        // atGroundLevel=true (colocar): referencia el bloque bajo los pies -
        // se asume solido, el nuevo bloque aparece justo en la posicion del
        // objetivo (cara UP de ese bloque).
        // atGroundLevel=false (activar): referencia el bloque que se acaba
        // de colocar (ya esta en la posicion del objetivo), para hacer clic
        // sobre el y detonarlo.
        val baseY = if (atGroundLevel) floor(refY).toInt() - 1 else floor(refY).toInt()
        val blockPosition = Vector3i.from(floor(refX).toInt(), baseY, floor(refZ).toInt())

        val packet = InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE
            actionType = 0 // click-block (colocar/usar contra un bloque existente)
            this.blockPosition = blockPosition
            blockFace = 1 // UP - ver Direction estandar de Bedrock (0=Down,1=Up,2=N,3=S,4=W,5=E)
            hotbarSlot = slot
            itemInHand = item
            playerPosition = session.localPlayer.vec3Position
            clickPosition = Vector3f.from(0.5f, 1f, 0.5f)
        }

        session.serverBound(packet)
        return true
    }
}
