package com.retrivedmods.wclient.game.module.misc

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import org.cloudburstmc.protocol.bedrock.packet.RespawnPacket

/**
 * Auto-confirms the respawn handshake instead of waiting for a manual tap on
 * the death screen's "Respawn" button.
 *
 * Bedrock's respawn flow is a 3-step packet handshake (verified against the
 * protocol classes bundled with this project):
 *   1. Server -> Client: RespawnPacket(state = SERVER_SEARCHING)
 *   2. Server -> Client: RespawnPacket(state = SERVER_READY, position = ...)
 *   3. Client -> Server: RespawnPacket(state = CLIENT_READY)
 * Step 3 is normally what happens when you tap "Respawn" on the death screen.
 * This module fires step 3 back automatically the instant step 2 arrives.
 *
 * Note: the death screen itself is drawn by the real Minecraft app (triggered
 * the moment your health hits 0), not by this overlay project, so it can
 * still flash briefly - this can't suppress that. What it removes is having
 * to manually read it and tap Respawn: the server usually finishes the
 * respawn and sends you back into the world before you'd have reacted anyway.
 *
 * We don't intercept/cancel the incoming packet - we let it reach the real
 * client too, alongside our own reply, so the game's own UI stays in sync
 * instead of possibly getting stuck on the death screen.
 */
class AutoRespawnModule : Module("auto_respawn", ModuleCategory.Misc) {

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet
        if (packet !is RespawnPacket) return

        // Temporary visibility: log every RespawnPacket we see, matched or not,
        // so we can tell from the in-game chat whether this is (a) never
        // seeing the packet, (b) an ID mismatch, or (c) sending CLIENT_READY
        // but the real Minecraft app's own death screen just not caring.
        session.displayClientMessage(
            "[AutoRespawn] state=${packet.state} pktId=${packet.runtimeEntityId} localId=${session.localPlayer.runtimeEntityId}"
        )

        if (packet.runtimeEntityId != session.localPlayer.runtimeEntityId) return
        if (packet.state != RespawnPacket.State.SERVER_READY) return

        session.serverBound(RespawnPacket().apply {
            runtimeEntityId = session.localPlayer.runtimeEntityId
            position = packet.position
            state = RespawnPacket.State.CLIENT_READY
        })

        session.displayClientMessage("[AutoRespawn] sent CLIENT_READY")
    }
}
