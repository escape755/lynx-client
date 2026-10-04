/*
 * © Project Lumina 2026 — GPLv3 Licensed
 * You may use, modify, and share this code under the GPL.
 *
 * Just know: changing names and colors doesn't make you a developer.
 * Think before you fork. Build something real — or don't bother.
 */

package com.project.lumina.client.game.module.impl.combat

import com.project.lumina.client.constructors.NetBound
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerHotbarPacket
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Mecanica compartida por Anchor Aura y Bed Aura: buscar un item que hace
 * explotar el bloque al usarse en la dimension equivocada (ancla de
 * respawn o cama), colocarlo junto a un objetivo y activarlo.
 *
 * Port directo de ExplosiveBlockPlacer de LynxClient (ya incluye el check
 * de daño propio y el swap-back al item original despues de activar, que
 * ahi se agregaron tomando la idea de BedAuraModule de MuCuteClient).
 *
 * LIMITACION CONOCIDA: ni Lumina ni LynxClient trackean el estado real de
 * los bloques del mundo (MuCuteClient si lo hace - requeriria un sistema
 * de chunks propio para igualarlo, fuera de alcance de este port).
 * blockPosition se asume (el bloque bajo los pies de referencia); si esa
 * posicion no es solida en el servidor real, el servidor simplemente
 * descarta la colocacion sin error.
 */
class ExplosiveBlockPlacer(
    private val session: NetBound,
    private val isTargetItem: (ItemData) -> Boolean
) {

    private val placedAt = HashMap<Long, Long>()
    private val slotBeforeSwitch = HashMap<Long, Int>()

    fun reset() {
        placedAt.clear()
        slotBeforeSwitch.clear()
    }

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
