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
import com.project.lumina.client.game.module.api.setting.stringValue
import org.cloudburstmc.protocol.bedrock.packet.TextPacket

/**
 * Port de FastCommand (LynxClient): un cuadrito de texto para escribir un
 * comando y mandarlo rapido. Al activar el switch manda el comando y se
 * apaga solo (se comporta como un boton "enviar", no como un toggle
 * persistente), listo para escribir el siguiente sin tener que apagarlo a
 * mano.
 *
 * Adaptado a la arquitectura de este proyecto, no copiado tal cual:
 * - Element.stringValue() aqui es de OPCIONES FIJAS (envuelve listValue por
 *   debajo, ver StringValue.kt) - a diferencia de LynxClient, que tiene un
 *   campo de texto libre. Por eso el campo de comando aqui es una lista de
 *   plantillas editable en el codigo en vez de texto libre: con options
 *   vacias, cualquier valor que no este en la lista colapsa silenciosamente
 *   al default (visto en StringValueDelegate.setValue), lo que habria dejado
 *   el campo atascado siempre en el mismo comando.
 * - No existe disableSilently() en este Element - se usa isEnabled = false
 *   directo.
 */
class FastCommandElement : Element(
    name = "FastCommand",
    category = CheatCategory.Misc
) {

    private var command by stringValue(
        "Command",
        "/home 55",
        listOf("/home 55", "/spawn", "/warp pvp", "/tpa accept", "/sell all")
    )
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

        // se comporta como un boton, no como un toggle que se queda prendido
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
