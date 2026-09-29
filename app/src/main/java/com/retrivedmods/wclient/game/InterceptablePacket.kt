package com.retrivedmods.wclient.game

import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket

/**
 * @param fromServer true si el paquete lo envió el SERVIDOR remoto hacia el juego;
 * false si lo envió el JUEGO hacia el servidor. Varios tipos de paquete viajan
 * en los dos sentidos (TextPacket, MovePlayerPacket, MobEquipmentPacket...) y
 * sin este dato un módulo no puede distinguir un mensaje que escribió el
 * jugador de uno que le llega de otro jugador.
 */
data class InterceptablePacket(val packet: BedrockPacket, val fromServer: Boolean = false) {

    var isIntercepted = false
        private set

    fun intercept() {
        isIntercepted = true
    }

}
