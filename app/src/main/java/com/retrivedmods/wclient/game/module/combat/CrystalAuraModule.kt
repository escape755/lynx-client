package com.retrivedmods.wclient.game.module.combat

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.entity.Entity
import com.retrivedmods.wclient.game.entity.EntityUnknown
import com.retrivedmods.wclient.game.entity.Player
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
 * Antes CrystalSmashModule: solo atacaba cristales que YA estuvieran en el
 * mundo. Renombrado y ampliado con la idea de AutoCrystal (Veyra, C++,
 * Category/Combat/AutoCrystal.cpp): si no hay ninguno cerca, coloca uno
 * junto al enemigo mas cercano - el bucle de ataque de mas abajo (ya
 * existente, sin tocar) lo detecta y lo rompe en el siguiente ciclo, sin
 * necesitar una fase de "activacion" aparte como Anchor/Bed.
 *
 * Tambien se porta la idea de no detonar si el daño calculado a UNO MISMO
 * es demasiado alto (selfDamageCap) - eso si es nuevo respecto al
 * CrystalSmash original.
 *
 * Lo que NO se porta de Veyra, y por que:
 * - El escaneo de hasta cientos de posiciones candidatas alrededor del
 *   objetivo para elegir la de mas daño: depende de consultar el bloque
 *   real del mundo en cada una (WorldUtil::getBlock). Este proyecto no
 *   trackea bloques (mismo hueco que ya se señalo para Anchor/Bed/Surround).
 *   Aqui se prueba una unica posicion (bajo los pies del objetivo) y se
 *   deja que el servidor la rechace si no es valida.
 * - La reduccion de daño por armadura/encantamientos del formula de
 *   explosion: Veyra usa el valor de armadura real de cada pieza que el
 *   objetivo lleva puesta (su SDK tiene un registro de items con eso). Este
 *   proyecto no tiene una tabla de valores de armadura, asi que el calculo
 *   de aqui asume objetivo SIN armadura - sobreestima el daño real a
 *   alguien armado. Para la comprobacion de daño propio esto es del lado
 *   seguro (si acaso, dispara de menos, nunca de mas).
 */
class CrystalAuraModule : Module("Crystal Aura", ModuleCategory.Combat) {

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
        // Se asume el bloque bajo los pies del objetivo como superficie
        // valida (normalmente obsidian/bedrock en combate de cristales) - no
        // hay forma de comprobarlo sin tracking de bloques, ver nota de
        // cabecera. Si no lo es, el servidor simplemente no coloca nada.
        val blockPosition = Vector3i.from(
            floor(target.posX).toInt(),
            floor(target.posY).toInt() - 1,
            floor(target.posZ).toInt()
        )

        session.serverBound(InventoryTransactionPacket().apply {
            transactionType = InventoryTransactionType.ITEM_USE
            actionType = 0
            this.blockPosition = blockPosition
            blockFace = 1 // UP
            hotbarSlot = slot
            itemInHand = item
            playerPosition = session.localPlayer.vec3Position
            clickPosition = Vector3f.from(0.5f, 1f, 0.5f)
        })
    }

    /**
     * Formula de daño de explosion real de Minecraft (radio de daño 12 para
     * end crystal), sin reduccion por armadura - ver nota de cabecera.
     * exposure fijo a 1 (sin raycast a bloques: mismo hueco de arriba).
     */
    private fun explosionDamage(cx: Float, cy: Float, cz: Float, target: Entity): Float {
        val explosionSize = 12f
        val dx = cx - target.posX
        val dy = cy - target.posY
        val dz = cz - target.posZ
        val dist = sqrt(dx * dx + dy * dy + dz * dz) / explosionSize
        if (dist > 1f) return 0f
        val exposure = 1f
        val impact = (1f - dist) * exposure
        return (((impact * impact + impact) / 2f) * 8f * explosionSize + 1f).coerceAtLeast(0f)
    }
}
