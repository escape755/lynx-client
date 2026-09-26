package com.retrivedmods.wclient.game.world

import android.util.Log
import com.retrivedmods.wclient.game.GameSession
import com.retrivedmods.wclient.game.entity.Entity
import com.retrivedmods.wclient.game.entity.EntityUnknown
import com.retrivedmods.wclient.game.entity.Item
import com.retrivedmods.wclient.game.entity.Player
import org.cloudburstmc.protocol.bedrock.packet.AddEntityPacket
import org.cloudburstmc.protocol.bedrock.packet.AddItemEntityPacket
import org.cloudburstmc.protocol.bedrock.packet.AddPlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.MobEffectPacket
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityAbsolutePacket
import org.cloudburstmc.protocol.bedrock.packet.MoveEntityDeltaPacket
import org.cloudburstmc.protocol.bedrock.packet.MovePlayerPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerListPacket
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityDataPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityLinkPacket
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket
import org.cloudburstmc.protocol.bedrock.packet.TakeItemEntityPacket
import org.cloudburstmc.protocol.bedrock.packet.UpdateAttributesPacket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Suppress("MemberVisibilityCanBePrivate")
class Level(val session: GameSession) {

    val entityMap = ConcurrentHashMap<Long, Entity>()

    val playerMap = ConcurrentHashMap<UUID, PlayerListPacket.Entry>()

    fun onDisconnect() {
        entityMap.clear()
        playerMap.clear()
    }

    fun onPacketBound(packet: BedrockPacket) {
        when (packet) {
            is StartGamePacket -> {
                entityMap.clear()
                playerMap.clear()
            }

            is AddEntityPacket -> {
                val entity = EntityUnknown(
                    packet.runtimeEntityId,
                    packet.uniqueEntityId,
                    packet.identifier
                ).apply {
                    move(packet.position)
                    rotate(packet.rotation)
                    handleSetData(packet.metadata)
                    handleSetAttribute(packet.attributes)
                }
                entityMap[packet.runtimeEntityId] = entity
            }

            is AddItemEntityPacket -> {
                val entity = Item(packet.runtimeEntityId, packet.uniqueEntityId).apply {
                    move(packet.position)
                    handleSetData(packet.metadata)
                }
                entityMap[packet.runtimeEntityId] = entity
            }

            is AddPlayerPacket -> {
                val entity = Player(
                    packet.runtimeEntityId,
                    packet.uniqueEntityId,
                    packet.uuid,
                    packet.username
                ).apply {
                    move(packet.position)
                    rotate(packet.rotation)
                    handleSetData(packet.metadata)
                }
                entityMap[packet.runtimeEntityId] = entity
            }

            is RemoveEntityPacket -> {
                val entityToRemove =
                    entityMap.values.find { it.uniqueEntityId == packet.uniqueEntityId } ?: return
                entityMap.remove(entityToRemove.runtimeEntityId)
            }

            is TakeItemEntityPacket -> {
                entityMap.remove(packet.itemRuntimeEntityId)
            }

            is PlayerListPacket -> {
                val add = packet.action == PlayerListPacket.Action.ADD
                packet.entries.forEach {
                    if (add) {
                        playerMap[it.uuid] = it
                    } else {
                        playerMap.remove(it.uuid)
                    }
                }
            }

            is MoveEntityAbsolutePacket,
            is MoveEntityDeltaPacket,
            is MovePlayerPacket,
            is SetEntityDataPacket,
            is UpdateAttributesPacket,
            is SetEntityLinkPacket,
            is MobEffectPacket -> dispatchToEntities(packet)

            else -> {}
        }
    }

    /**
     * Antes esto se llamaba para TODO tipo de paquete (el else de arriba),
     * recorriendo entityMap entero aunque ningún Entity reaccionara a ese
     * paquete. Con muchos jugadores/mobs cerca eso es trabajo real en cada
     * paquete que pasa por el relay. Ahora solo se llama para los tipos de
     * paquete que Entity/Player realmente manejan, y el try/catch evita que
     * un solo entity con datos raros tire abajo el procesamiento del resto
     * (y de los módulos, que corren después en GameSession).
     */
    private fun dispatchToEntities(packet: BedrockPacket) {
        entityMap.values.forEach { entity ->
            try {
                entity.onPacketBound(packet)
            } catch (e: Exception) {
                Log.e(
                    "Level",
                    "Entity ${entity.runtimeEntityId} (${entity::class.simpleName}) failed to handle ${packet::class.simpleName}",
                    e
                )
            }
        }
    }

}