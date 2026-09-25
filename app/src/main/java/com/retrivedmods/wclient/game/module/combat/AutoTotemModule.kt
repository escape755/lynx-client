package com.retrivedmods.wclient.game.module.combat

import android.util.Log
import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.entity.LocalPlayer
import com.retrivedmods.wclient.game.inventory.PlayerInventory
import com.retrivedmods.wclient.game.utils.constants.Attribute
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

class AutoTotemModule : Module("Auto Totem", ModuleCategory.Combat) {

    private var delay by intValue("Delay", 100, 0..1000)
    private var onlyWhenLowHealth by boolValue("Only When Low Health", false)
    private var healthThreshold by intValue("Health Threshold", 10, 1..20)
    private var replaceOffhand by boolValue("Replace Offhand", true)

    private var lastTotemTime = 0L

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet
        if (packet !is PlayerAuthInputPacket) return

        val player: LocalPlayer = session.localPlayer
        val offhandItem = player.inventory.offhand

        // Nothing to do if the offhand already has a totem. Checked first and
        // NOT gated by `delay`: before, the delay blocked this check too, so
        // a totem that popped while the previous cooldown was still running
        // could sit unfilled for up to `delay` ms instead of being replaced
        // right away.
        if (isTotem(offhandItem)) return

        if (onlyWhenLowHealth) {
            val health = player.attributes[Attribute.HEALTH]?.value ?: 20f
            if (health > healthThreshold) return
        }

        val now = System.currentTimeMillis()
        if (now - lastTotemTime < delay) return

        if (!replaceOffhand && offhandItem != ItemData.AIR) return

        val totemSlot = findTotemInInventory(player) ?: return

        if (moveTotemToOffhand(player, totemSlot)) {
            lastTotemTime = now
        }
    }

    private fun isTotem(item: ItemData): Boolean {
        return item != ItemData.AIR && item.definition?.identifier == "minecraft:totem_of_undying"
    }

    private fun findTotemInInventory(player: LocalPlayer): Int? {
        val inv = player.inventory
        for (i in 0 until 36) {
            if (isTotem(inv.content[i])) return i
        }
        return null
    }

    private fun moveTotemToOffhand(player: LocalPlayer, sourceSlot: Int): Boolean {
        return try {
            val inv = player.inventory
            val sourceItem = inv.content[sourceSlot]
            if (!isTotem(sourceItem)) return false

            val previousOffhandItem = inv.offhand

            inv.moveItem(sourceSlot, PlayerInventory.SLOT_OFFHAND, inv, session)

            // inv.moveItem() only builds and sends the network request - it
            // never updates our own copy of the inventory. That gap is why
            // isTotem(offhand) kept seeing stale data until the server's
            // confirmation round-tripped back, which the delay was really
            // masking. Mirror the swap locally so the very next packet
            // already sees the correct offhand/source contents.
            inv.offhand = sourceItem
            inv.content[sourceSlot] = previousOffhandItem

            true
        } catch (e: Exception) {
            Log.w("AutoTotemModule", "Failed to move totem to offhand", e)
            false
        }
    }
}
