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
import com.project.lumina.client.game.entity.Entity
import com.project.lumina.client.game.entity.EntityUnknown
import com.project.lumina.client.game.entity.Player
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.math.vector.Vector3i
import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerHotbarPacket
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * No existia en Lumina. Port de CrystalAuraModule (LynxClient), que a su
 * vez tomo la idea de colocar+romper de AutoCrystal (Veyra, C++) y el check
 * de daño propio de BedAuraModule (MuCuteClient). Ataca cristales que ya
 * esten en el mundo; si no hay ninguno cerca, coloca uno junto al enemigo
 * mas cercano - el mismo bucle de ataque lo detecta y rompe en el siguiente
 * ciclo.
 *
 * LIMITACION: ni Lumina ni LynxClient trackean bloques reales del mundo
 * (MuCuteClient si). blockPosition se asume (bajo los pies del objetivo);
 * si no es valida, el servidor descarta la colocacion sin error. El calculo
 * de daño tampoco resta por armadura (sin tabla de valores de item en este
 * proyecto) - sobreestima el daño, que para el check de seguridad propia es
 * el lado prudente de equivocarse.
 */
class CrystalAuraElement : Element(
    name = "CrystalAura",
    category = CheatCategory.Combat
) {

    private var rangeValue by floatValue("Range", 4.0f, 2f..7f)
    private var attackInterval by intValue("Delay", 5, 1..20)
    private var cpsValue by intValue("CPS", 10, 1..20)
    private var packets by intValue("Packets", 1, 1..10)
    private var placeCrystals by boolValue("Place", true)
    private var selfDamageCap by floatValue("Self Damage Cap", 18f, 2f..36f)
    private var minEnemyDamage by floatValue("Min Enemy Damage", 4f, 0f..36f)

    private var lastAttackTime = 0L
    private var lastPlaceTime = 0L

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet
        if (packet !is PlayerAuthInputPacket) return

        val currentTime = System.currentTimeMillis()
        val minAttackDelay = 1000L / cpsValue

        if (packet.tick % attackInterval == 0L && (currentTime - lastAttackTime) >= minAttackDelay) {
            val crystals = searchForCrystals()

            if (crystals.isNotEmpty()) {
                var attacked = false
                crystals.forEach { crystal ->
                    val selfDamage = explosionDamage(crystal.posX, crystal.posY, crystal.posZ, session.localPlayer)
                    if (selfDamage > selfDamageCap) return@forEach

                    repeat(packets) {
                        session.localPlayer.attack(crystal)
                    }
                    attacked = true
                }
                if (attacked) lastAttackTime = currentTime
            } else if (placeCrystals && currentTime - lastPlaceTime >= minAttackDelay) {
                findNearestEnemy()?.let { enemy ->
                    val predictedDamage = explosionDamage(enemy.posX, enemy.posY + 1f, enemy.posZ, enemy)
                    if (predictedDamage >= minEnemyDamage) {
                        tryPlaceCrystal(enemy)
                        lastPlaceTime = currentTime
                    }
                }
            }
        }
    }

    private fun searchForCrystals(): List<Entity> {
        return session.level.entityMap.values
            .filter { entity ->
                entity.distance(session.localPlayer) < rangeValue &&
                        entity is EntityUnknown &&
                        entity.identifier == "minecraft:end_crystal"
            }
    }

    private fun findNearestEnemy(): Entity? {
        return session.level.entityMap.values
            .filter { it is Player && it.distance(session.localPlayer) <= rangeValue }
            .minByOrNull { it.distance(session.localPlayer) }
    }

    private fun tryPlaceCrystal(target: Entity) {
        val inv = session.localPlayer.inventory
        val slot = inv.searchForItemInHotbar {
            it != ItemData.AIR && it.definition?.identifier == "minecraft:end_crystal"
        } ?: return

        if (inv.heldItemSlot != slot) {
            session.serverBound(PlayerHotbarPacket().apply {
                selectedHotbarSlot = slot
                containerId = 0
                selectHotbarSlot = true
            })
        }

        val item = inv.content[slot]
        val blockPosition = Vector3i.from(
            floor(target.posX).toInt(),
            floor(target.posY).toInt() - 1,
            floor(target.posZ).toInt()
        )

        session.serverBound(InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE
            actionType = 0
            this.blockPosition = blockPosition
            blockFace = 1
            hotbarSlot = slot
            itemInHand = item
            playerPosition = session.localPlayer.vec3Position
            clickPosition = Vector3f.from(0.5f, 1f, 0.5f)
        })
    }

    private fun explosionDamage(cx: Float, cy: Float, cz: Float, target: Entity): Float {
        val explosionSize = 12f
        val dx = cx - target.posX
        val dy = cy - target.posY
        val dz = cz - target.posZ
        val dist = sqrt(dx * dx + dy * dy + dz * dz) / explosionSize
        if (dist > 1f) return 0f
        val impact = 1f - dist
        return (((impact * impact + impact) / 2f) * 8f * explosionSize + 1f).coerceAtLeast(0f)
    }
}
