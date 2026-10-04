/*
 * © Project Lumina 2026 — GPLv3 Licensed
 * You may use, modify, and share this code under the GPL.
 *
 * Just know: changing names and colors doesn't make you a developer.
 * Think before you fork. Build something real — or don't bother.
 */

package com.project.lumina.client.game.module.impl.misc

import com.project.lumina.client.constructors.Element
import com.project.lumina.client.constructors.CheatCategory
import com.project.lumina.client.game.InterceptablePacket
import com.project.lumina.client.game.entity.Player
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.LevelEvent
import org.cloudburstmc.protocol.bedrock.data.entity.EntityEventType
import org.cloudburstmc.protocol.bedrock.packet.EntityEventPacket
import org.cloudburstmc.protocol.bedrock.packet.LevelEventPacket
import org.cloudburstmc.protocol.bedrock.packet.RemoveEntityPacket
import org.cloudburstmc.protocol.bedrock.packet.RespawnPacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import java.util.UUID
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Port de PopCounter (LynxClient, que a su vez venia de Gato Client Mobile
 * 1.8). Cuenta cuantos totems se "pop-ean" por jugador, avisa en el chat
 * del juego, y opcionalmente manda una burla al chat del SERVER cuando
 * alguien pop-ea o muere.
 *
 * Una cosa se quito respecto a la version de LynxClient: ahi distinguia
 * amigos via FriendManager.isFriend(uuid) para no contarlos/avisar de
 * ellos. Este proyecto no tiene un sistema de amigos - en vez de inventar
 * uno nuevo solo para esto, se quito esa distincion: todos los jugadores
 * (menos uno mismo) se tratan igual.
 *
 * El posX/Y/Z de LocalPlayer aqui tambien es a nivel de OJOS (viene de
 * PlayerAuthInputPacket.position sin ajuste, igual que en LynxClient), asi
 * que se aplica el mismo offset de 1.62 bloques solo para el jugador local,
 * para comparar contra la posicion del sonido del totem (a nivel de pies)
 * en igualdad de condiciones con el resto de entidades.
 */
class PopCounterElement : Element(
    name = "PopCounter",
    category = CheatCategory.Misc
) {

    private var sendPops by boolValue("Send Pop Message", true)
    private var sendPopsOnDeath by boolValue("Send Death Message", true)
    private var countSelf by boolValue("Track Self", false)
    private var actorEventOnly by boolValue("ActorEvent", false)
    private var useRandomTaunts by boolValue("Random Taunts", true)

    private var popMessage by stringValue(
        "Game Pop Message",
        "@!player! just popped !pops! !totem!",
        listOf("@!player! just popped !pops! !totem!")
    )
    private var deathMessage by stringValue(
        "Game Death Message",
        "@!player! just died after popping !pops! !totem!",
        listOf("@!player! just died after popping !pops! !totem!")
    )

    private val popTaunts = listOf(
        "\$c!player! \$7casi se va al cielo, menos mal que tenia totem. Van \$c!pops!",
        "\$7Otro totem gastado por \$c!player!\$7... a este paso se queda sin vidas de gato",
        "\$c!player! \$7le rezo a un totem y funciono. \$c!pops! \$7hasta ahora",
        "\$7Se escucho un 'pop' sospechoso cerca de \$c!player!\$7. Totem #\$c!pops!",
        "\$c!player! \$7sobrevive de milagro otra vez, \$c!pops! \$7totems y contando",
        "\$7\$c!player! \$7acaba de quemar otro totem. Cuenta: \$c!pops!",
        "\$c!player! \$7pago peaje con un totem para seguir vivo. \$c!pops! \$7pagados",
        "\$7Ese totem le acaba de salvar el pellejo a \$c!player!\$7. Van \$c!pops!",
        "\$c!player! \$7juega con fuego y totems, ya lleva \$c!pops!",
        "\$7Pop numero \$c!pops! \$7de \$c!player!"
    )

    private val deathTaunts = listOf(
        "\$c!player! \$7se murio igual despues de gastar \$c!pops! \$7totems, todo ese drama para nada",
        "\$7Ni \$c!pops! \$7totems pudieron salvar a \$c!player!\$7. GG",
        "\$c!player! \$7se le acabo la suerte junto con los totems. \$c!pops! \$7desperdiciados",
        "\$7RIP \$c!player!\$7, se fue con \$c!pops! \$7totems gastados en el bolsillo",
        "\$c!player! \$7demostro que hasta con \$c!pops! \$7totems se puede perder igual",
        "\$7Se acabo la funcion para \$c!player! \$7despues de \$c!pops! \$7totems inutiles"
    )

    // runtimeEntityId -> pops
    private val totemMap = HashMap<Long, Int>()

    // uniqueEntityId -> identidad cacheada. RemoveEntityPacket solo trae el
    // unique id, y para cuando los modulos lo ven la entidad ya desaparecio
    // del mapa de Level, asi que se cachea mientras el jugador sigue vivo.
    private val entityCache = HashMap<Long, PopCandidate>()

    private data class PopCandidate(
        val runtimeId: Long,
        val uniqueId: Long,
        val uuid: UUID,
        val username: String
    )

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        when (val packet = interceptablePacket.packet) {
            is LevelEventPacket -> {
                if (!actorEventOnly &&
                    packet.type == LevelEvent.SOUND_TOTEM_USED &&
                    (packet.position.x != 0f || packet.position.y != 0f || packet.position.z != 0f)
                ) {
                    handleLevelEventPop(packet)
                }
            }

            is EntityEventPacket -> {
                if (actorEventOnly && packet.type == EntityEventType.CONSUME_TOTEM) {
                    handleActorEventPop(packet)
                }
            }

            is RemoveEntityPacket -> handleRemove(packet)

            is RespawnPacket -> {
                totemMap[session.localPlayer.runtimeEntityId] = 0
            }

            else -> {}
        }
    }

    private fun handleLevelEventPop(packet: LevelEventPacket) {
        val eventPos = packet.position
        val local = session.localPlayer

        val candidates = mutableListOf<PopCandidate>()
        if (local.runtimeEntityId != 0L) {
            candidates.add(PopCandidate(local.runtimeEntityId, local.uniqueEntityId, local.uuid, local.username))
        }
        session.level.entityMap.values.forEach { entity ->
            if (entity is Player) {
                candidates.add(PopCandidate(entity.runtimeEntityId, entity.uniqueEntityId, entity.uuid, entity.username))
            }
        }

        var best: PopCandidate? = null
        var bestDist = Float.MAX_VALUE
        for (candidate in candidates) {
            val pos = if (candidate.runtimeId == local.runtimeEntityId) {
                Vector3f.from(local.posX, local.posY - 1.62f, local.posZ)
            } else {
                session.level.entityMap[candidate.runtimeId]?.vec3Position ?: continue
            }
            val dx = floor(pos.x) - eventPos.x
            val dy = floor(pos.y) - 1f - eventPos.y
            val dz = floor(pos.z) - eventPos.z
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist > 10f) continue
            if (dist < bestDist) {
                bestDist = dist
                best = candidate
            }
        }

        best?.let {
            entityCache[it.uniqueId] = it
            onActorPop(it.runtimeId, it.username, it.uniqueId == local.uniqueEntityId)
        }
    }

    private fun handleActorEventPop(packet: EntityEventPacket) {
        val runtimeId = packet.runtimeEntityId
        val local = session.localPlayer

        if (runtimeId == local.runtimeEntityId) {
            entityCache[local.uniqueEntityId] =
                PopCandidate(runtimeId, local.uniqueEntityId, local.uuid, local.username)
            onActorPop(runtimeId, local.username, true)
            return
        }

        val entity = session.level.entityMap[runtimeId] as? Player ?: return
        entityCache[entity.uniqueEntityId] =
            PopCandidate(runtimeId, entity.uniqueEntityId, entity.uuid, entity.username)
        onActorPop(runtimeId, entity.username, false)
    }

    private fun handleRemove(packet: RemoveEntityPacket) {
        val cached = entityCache[packet.uniqueEntityId] ?: return
        val pops = totemMap[cached.runtimeId] ?: return
        if (pops == 0) return

        val isSelf = packet.uniqueEntityId == session.localPlayer.uniqueEntityId
        if (isSelf) {
            totemMap[cached.runtimeId] = 0
            return
        }

        val totemWord = if (pops > 1) "totems" else "totem"

        if (isEnabled) {
            session.displayClientMessage(
                "§c[+] §c${cached.username} §fdied after popping §b$pops §f$totemWord!"
            )
        }

        if (sendPopsOnDeath) {
            val template = if (useRandomTaunts) deathTaunts.random() else deathMessage
            sendGameChat(sanitize(template, cached.username, pops))
        }

        totemMap[cached.runtimeId] = 0
    }

    /** Se cuenta sin importar isEnabled; solo los mensajes se condicionan. */
    private fun onActorPop(runtimeId: Long, username: String, isSelf: Boolean) {
        val pops = (totemMap[runtimeId] ?: 0) + 1
        totemMap[runtimeId] = pops
        val totemWord = if (pops > 1) "totems" else "totem"

        if (!isEnabled) return

        if (isSelf) {
            if (countSelf) {
                session.displayClientMessage("§6[!] §9You §fpopped §b$pops §f$totemWord!")
            }
            return
        }

        session.displayClientMessage("§6[!] §c$username §fpopped §b$pops §f$totemWord!")
        if (sendPops) {
            val template = if (useRandomTaunts) popTaunts.random() else popMessage
            sendGameChat(sanitize(template, username, pops))
        }
    }

    private fun sanitize(message: String, name: String, pops: Int): String =
        message
            .replace("$", "§")
            .replace("!clientname!", "Lumina Client")
            .replace("!player!", name)
            .replace("!pops!", pops.toString())
            .replace("!totem!", if (pops > 1) "totems" else "totem")

    private fun sendGameChat(message: String) {
        session.serverBound(TextPacket().apply {
            type = TextPacket.Type.CHAT
            // sourceName/xuid quedan null si no se asignan, y algunos
            // codecs rechazan eso al codificar un CHAT (visto en LynxClient
            // con el codec v898 - el paquete nunca salia, en silencio).
            // Cadena vacia es lo que manda un cliente real aqui de todas
            // formas; el server rellena el nombre/xuid real al reenviar.
            sourceName = ""
            xuid = ""
        }.also { it.setMessage(message) })
    }

    fun getTotemPops(runtimeId: Long): Int = totemMap[runtimeId] ?: 0
}
