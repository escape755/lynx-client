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
import org.cloudburstmc.protocol.bedrock.packet.RespawnPacket

/**
 * Port de AutoRespawn (LynxClient): confirma el handshake de respawn solo,
 * sin esperar el tap manual en el boton "Respawn" de la pantalla de muerte.
 *
 * El handshake de respawn en Bedrock son 3 pasos (mismo protocolo
 * cloudburstmc que ya usaba LynxClient, confirmado contra RespawnPacket.java
 * vendorizado en este mismo proyecto):
 *   1. Server -> Client: RespawnPacket(state = SERVER_SEARCHING)
 *   2. Server -> Client: RespawnPacket(state = SERVER_READY, position = ...)
 *   3. Client -> Server: RespawnPacket(state = CLIENT_READY)
 * El paso 3 es lo que pasa normalmente al tocar "Respawn". Este modulo
 * manda ese paso 3 en el instante en que llega el paso 2.
 *
 * No se intercepta/cancela el paquete entrante - se deja llegar al juego
 * real tambien, junto a nuestra respuesta, para que la UI del juego quede
 * sincronizada en vez de quedarse pegada en la pantalla de muerte.
 */
class AutoRespawnElement : Element(
    name = "AutoRespawn",
    category = CheatCategory.Misc
) {

    private var showFeedback by boolValue("Show Feedback", true)

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet
        if (packet !is RespawnPacket) return
        if (packet.state != RespawnPacket.State.SERVER_READY) return

        session.serverBound(RespawnPacket().apply {
            runtimeEntityId = session.localPlayer.runtimeEntityId
            position = packet.position
            state = RespawnPacket.State.CLIENT_READY
        })

        if (showFeedback) {
            session.displayClientMessage("[AutoRespawn] sent CLIENT_READY")
        }
    }
}
