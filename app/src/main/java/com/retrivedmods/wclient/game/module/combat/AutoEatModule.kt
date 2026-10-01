package com.retrivedmods.wclient.game.module.combat

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.utils.constants.Attribute
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerHotbarPacket

/**
 * Come automaticamente cuando el hambre baja del umbral configurado.
 *
 * Mecanica: PlayerAuthInputPacket es el paquete de input REAL que el juego
 * manda cada tick (~20/s); ese mismo objeto sigue su camino hacia el
 * servidor despues de que los modulos lo procesen (ver GameSession). En vez
 * de construir un paquete propio, este modulo selecciona el slot de comida
 * (PlayerHotbarPacket, igual que si el jugador hubiera pulsado una tecla) y
 * le añade la flag START_USING_ITEM al paquete, igual que ya hace
 * PlayerInventory.kt con PERFORM_ITEM_STACK_REQUEST (mutar inputData de un
 * paquete real de paso, no inventar uno sintetico).
 *
 * v2: la version anterior añadia la flag en CADA tick mientras el hambre
 * seguia baja. Sospecha (no confirmada por log, pero es el cambio mas
 * razonable dado el sintoma "no come nada"): si el servidor interpreta
 * cada aparicion de START_USING_ITEM como un "empezar a usar" nuevo en vez
 * de "seguir sosteniendo", el temporizador de comer se reinicia cada tick y
 * nunca llega a completarse. Ahora se sostiene la flag por la duracion real
 * de comer en Bedrock (32 ticks, 1.6s) y despues se suelta, igual que
 * haria un jugador real soltando el click tras la animacion.
 */
class AutoEatModule : Module("Auto Eat", ModuleCategory.Combat) {

    private var hungerThreshold by intValue("Hunger Threshold", 14, 0..20)
    private var delay by intValue("Delay", 200, 0..2000)

    private var eatingSinceTick: Long? = null
    private var lastFinishedAt = 0L

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet
        if (packet !is PlayerAuthInputPacket) return

        val holdStart = eatingSinceTick
        if (holdStart != null) {
            if (packet.tick - holdStart < EAT_DURATION_TICKS) {
                packet.inputData.add(PlayerAuthInputData.START_USING_ITEM)
            } else {
                eatingSinceTick = null
                lastFinishedAt = System.currentTimeMillis()
            }
            return
        }

        // El jugador (u otro modulo) ya esta usando un item este tick - no
        // pisarlo con nuestra propia flag, misma logica de no-conflicto que
        // ya se aplico en GameSession para no tapar fallos entre sistemas.
        if (packet.inputData.contains(PlayerAuthInputData.START_USING_ITEM)) return

        val player = session.localPlayer
        val hunger = player.attributes[Attribute.HUNGER]?.value ?: 20f
        if (hunger > hungerThreshold) return

        if (System.currentTimeMillis() - lastFinishedAt < delay) return

        val inv = player.inventory
        val foodSlot = inv.searchForItemInHotbar { isFood(it) } ?: return

        if (inv.heldItemSlot != foodSlot) {
            session.serverBound(PlayerHotbarPacket().apply {
                selectedHotbarSlot = foodSlot
                containerId = 0
                selectHotbarSlot = true
            })
        }

        packet.inputData.add(PlayerAuthInputData.START_USING_ITEM)
        eatingSinceTick = packet.tick
    }

    override fun onDisabled() {
        super.onDisabled()
        eatingSinceTick = null
    }

    private fun isFood(item: ItemData): Boolean {
        if (item == ItemData.AIR) return false
        val id = item.definition?.identifier ?: return false
        return id in FOOD_ITEMS
    }

    private companion object {
        // Duracion real de la animacion/temporizador de comer en Bedrock.
        const val EAT_DURATION_TICKS = 32L

        // Identificadores de comida mas comunes en supervivencia/PVP Bedrock.
        // Mismo tipo de lista fija que ya usaba ChestStealerModule.SKYWARS_FOOD
        // para esta version del protocolo - no hay flag "isFood" en ItemData.
        val FOOD_ITEMS = setOf(
            "minecraft:cooked_beef", "minecraft:cooked_porkchop", "minecraft:cooked_chicken",
            "minecraft:cooked_mutton", "minecraft:cooked_rabbit", "minecraft:cooked_cod",
            "minecraft:cooked_salmon", "minecraft:bread", "minecraft:apple",
            "minecraft:golden_apple", "minecraft:enchanted_golden_apple", "minecraft:carrot",
            "minecraft:potato", "minecraft:baked_potato", "minecraft:beetroot",
            "minecraft:beetroot_soup", "minecraft:mushroom_stew", "minecraft:rabbit_stew",
            "minecraft:melon_slice", "minecraft:sweet_berries", "minecraft:glow_berries",
            "minecraft:pumpkin_pie", "minecraft:cookie", "minecraft:dried_kelp",
            "minecraft:beef", "minecraft:porkchop", "minecraft:chicken",
            "minecraft:mutton", "minecraft:rabbit", "minecraft:cod", "minecraft:salmon"
        )
    }
}
