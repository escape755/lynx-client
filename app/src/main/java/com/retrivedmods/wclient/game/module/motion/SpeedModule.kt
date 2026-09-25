package com.retrivedmods.wclient.game.module.motion


import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import org.cloudburstmc.math.vector.Vector3f
import org.cloudburstmc.protocol.bedrock.data.PlayerAuthInputData
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.SetEntityMotionPacket

class SpeedModule : Module("speed", ModuleCategory.Motion) {

    private var speedValue by floatValue("speed", 1.3f, 0.1f..5f)


    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet

        if (packet is PlayerAuthInputPacket) {

            if (packet.motion.length() > 0.0 && packet.inputData.contains(PlayerAuthInputData.VERTICAL_COLLISION)) {


                val motionPacket = SetEntityMotionPacket().apply {
                    runtimeEntityId = session.localPlayer.runtimeEntityId
                    motion = Vector3f.from(
                        session.localPlayer.motionX.toDouble() * speedValue,
                        session.localPlayer.motionY.toDouble(),
                        session.localPlayer.motionZ.toDouble() * speedValue
                    )
                }


                session.clientBound(motionPacket)
            }
        }
    }
}