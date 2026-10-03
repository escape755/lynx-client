package com.retrivedmods.wclient.game.module.combat

import com.retrivedmods.wclient.game.GameSession
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerHotbarPacket
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Mecánica compartida por Anchor Aura, Anchor Helper y Bed Aura: buscar un
 * item que hace explotar el bloque al usarse en la dimensión equivocada
 * (ancla de respawn o cama), colocarlo junto a un objetivo y activarlo.
 *
 * NO es un Module - lo instancia cada módulo (mismo patrón que
 * PredictiveRotator para las 3 Aura), cada uno con su propio identifier,
 * settings y lista de objetivos/gating.
 *
 * v2: se le agregaron dos ideas de BedAuraModule (MuCuteClient) que no
 * dependen de su tracking de bloques (eso se dejo fuera, ver analisis):
 * - chequeo de daño antes de colocar (misma formula real de explosion que
 *   ya usaba CrystalAuraModule, sin reduccion por armadura por la misma
 *   razon ya explicada ahi).
 * - volver al item que tenias antes, despues de activar (antes te dejaba
 *   con el ancla/cama en la mano).
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

    // placementKey -> slot que tenias seleccionado ANTES de que esta clase
    // cambiara al ancla/cama, solo cuando el cambio lo hizo ella (Aura, no
    // Helper). Se usa para volver a ese slot despues de activar.
    private val slotBeforeSwitch = HashMap<Long, Int>()

    fun reset() {
        placedAt.clear()
        slotBeforeSwitch.clear()
    }

    /**
     * @param refX/Y/Z posicion de referencia (pies del objetivo, o del jugador local para Surround)
     * @param currentTick tick actual (PlayerAuthInputPacket.tick de este mismo paquete)
     * @param triggerAfterTicks cuantos ticks esperar entre colocar y activar
     * @param requireManualHold si es true, solo actua cuando el hotbar YA tiene el item
     *        seleccionado a mano (Anchor Helper); si es false, cambia de slot el solo (Aura)
     * @param placementKey clave estable para esta secuencia colocar->activar
     *        (p.ej. runtimeEntityId del objetivo, o una constante fija para Surround)
     * @param selfDamageCap si el daño calculado a UNO MISMO en la posicion de colocacion
     *        supera esto, no se coloca nada. null desactiva el chequeo (p.ej. Surround,
     *        que no explota nada).
     * @return true si mando algun paquete este tick
     */
    fun tryPlaceAndTrigger(
        refX: Float,
        refY: Float,
        refZ: Float,
        currentTick: Long,
        triggerAfterTicks: Long,
        requireManualHold: Boolean,
        placementKey: Long,
        selfDamageCap: Float? = null
    ): Boolean {
        val inv = session.localPlayer.inventory

        val slot = inv.searchForItemInHotbar { isTargetItem(it) } ?: run {
            placedAt.remove(placementKey)
            slotBeforeSwitch.remove(placementKey)
            return false
        }

        val phase = placedAt[placementKey]
        if (phase != null) {
            if (currentTick - phase < triggerAfterTicks) return false
            placedAt.remove(placementKey)
            val sent = sendUse(slot, inv.content[slot], refX, refY, refZ, atGroundLevel = false)
            if (sent) {
                slotBeforeSwitch.remove(placementKey)?.let { original ->
                    if (original != slot) {
                        session.serverBound(PlayerHotbarPacket().apply {
                            selectedHotbarSlot = original
                            containerId = 0
                            selectHotbarSlot = true
                        })
                    }
                }
            }
            return sent
        }

        if (requireManualHold && inv.heldItemSlot != slot) return false

        if (selfDamageCap != null) {
            val selfDamage = explosionDamage(refX, refY, refZ, session.localPlayer.posX, session.localPlayer.posY, session.localPlayer.posZ)
            if (selfDamage > selfDamageCap) return false
        }

        if (!requireManualHold && inv.heldItemSlot != slot) {
            slotBeforeSwitch[placementKey] = inv.heldItemSlot
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

        val baseY = if (atGroundLevel) floor(refY).toInt() - 1 else floor(refY).toInt()
        val blockPosition = Vector3i.from(floor(refX).toInt(), baseY, floor(refZ).toInt())

        val packet = InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE
            actionType = 0
            this.blockPosition = blockPosition
            blockFace = 1
            hotbarSlot = slot
            itemInHand = item
            playerPosition = session.localPlayer.vec3Position
            clickPosition = Vector3f.from(0.5f, 1f, 0.5f)
        }

        session.serverBound(packet)
        return true
    }

    /** Misma formula real de explosion de Minecraft que CrystalAuraModule (radio 12, sin armadura). */
    private fun explosionDamage(cx: Float, cy: Float, cz: Float, tx: Float, ty: Float, tz: Float): Float {
        val explosionSize = 12f
        val dx = cx - tx
        val dy = cy - ty
        val dz = cz - tz
        val dist = sqrt(dx * dx + dy * dy + dz * dz) / explosionSize
        if (dist > 1f) return 0f
        val impact = 1f - dist
        return (((impact * impact + impact) / 2f) * 8f * explosionSize + 1f).coerceAtLeast(0f)
    }
}
