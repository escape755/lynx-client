package com.retrivedmods.wclient.game.module.combat


import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

class AntiCrystalModule : Module("anti_crystal", ModuleCategory.Combat) {

    private var ylevel by floatValue("ylevel", 0.4f, 0.1f..1.61f)

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) {
            return
        }

        val packet = interceptablePacket.packet
        if (packet is PlayerAuthInputPacket) {
            packet.position.add(0.0, -ylevel.toDouble(), 0.0)
        }
    }

}