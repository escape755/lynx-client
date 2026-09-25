package com.retrivedmods.wclient.game.module.motion

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket

class SprintModule : Module("sprint", ModuleCategory.Motion) {

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        val packet = interceptablePacket.packet
        if (packet is PlayerAuthInputPacket && isEnabled) {
            packet.inputData.add(PlayerAuthInputData.SPRINTING)
            packet.inputData.add(PlayerAuthInputData.START_SPRINTING)
        } else if (packet is PlayerAuthInputPacket && !isEnabled) {
            packet.inputData.add(PlayerAuthInputData.STOP_SPRINTING)
        }
    }
}