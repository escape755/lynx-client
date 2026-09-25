package com.retrivedmods.wclient.game.module.misc

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import org.cloudburstmc.protocol.bedrock.packet.TextPacket

/**
 * Módulo pedido: un cuadrito de texto para escribir un comando y ejecutarlo
 * rápido. Al activar el switch, manda el comando escrito y se apaga solo
 * (se comporta como un botón "enviar", no como un toggle persistente) -
 * así queda listo para escribir el próximo sin tener que apagarlo a mano.
 */
class FastCommandModule : Module("FastCommand", ModuleCategory.Misc) {

    private var command by stringValue("Command", "/home 55", listOf())
    private var showFeedback by boolValue("Show Feedback", true)

    override fun onEnabled() {
        super.onEnabled()

        if (!isSessionCreated) {
            isEnabled = false
            return
        }

        val typed = command.trim()
        if (typed.isEmpty()) {
            isEnabled = false
            return
        }

        val toSend = if (typed.startsWith("/")) typed else "/$typed"
        sendCommand(toSend)

        if (showFeedback) {
            session.displayClientMessage("[FastCommand] enviado: $toSend")
        }

        // se comporta como un botón, no como un toggle que se queda prendido
        isEnabled = false
    }

    private fun sendCommand(text: String) {
        val textPacket = TextPacket()
        textPacket.type = TextPacket.Type.CHAT
        textPacket.sourceName = ""
        textPacket.message = text
        textPacket.xuid = ""
        textPacket.platformChatId = ""
        textPacket.needsTranslation = false

        session.serverBound(textPacket)
    }

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        // no necesita escuchar paquetes, todo pasa en onEnabled()
    }
}
