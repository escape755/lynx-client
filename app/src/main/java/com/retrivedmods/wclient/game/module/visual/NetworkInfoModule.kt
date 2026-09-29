package com.retrivedmods.wclient.game.module.visual

import com.retrivedmods.wclient.game.InterceptablePacket
import com.retrivedmods.wclient.game.Module
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.ActionBarManager
import org.cloudburstmc.protocol.bedrock.packet.NetworkStackLatencyPacket
import org.cloudburstmc.protocol.bedrock.packet.PlayerAuthInputPacket
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket

class NetworkInfoModule : Module("network_info", ModuleCategory.Visual) {

    private var lastDisplayTime = 0L
    private val displayInterval = 500L
    private val colorStyle by boolValue("colored_text", true)
    private val showPacketCounts by boolValue("show_packets", true)

    private var incomingPackets = 0
    private var outgoingPackets = 0
    private var lastPacketCountReset = 0L
    private val packetCountInterval = 1000L

    // Real round-trip time via Bedrock's own NetworkStackLatencyPacket:
    // the server sends one with fromServer=true and a timestamp, and the
    // client echoes the exact same packet back with fromServer=false. We
    // just watch that exchange go by - no extra packets of our own - and
    // time it with our own clock on both ends, so server/client clock skew
    // never enters into it.
    private var pendingRequestTimestamp: Long? = null
    private var pendingRequestSeenAt = 0L
    private var currentPing = -1L

    override fun beforePacketBound(interceptablePacket: InterceptablePacket) {
        if (!isEnabled) return

        val packet = interceptablePacket.packet
        incomingPackets++

        if (packet is NetworkStackLatencyPacket) {
            if (packet.isFromServer) {
                pendingRequestTimestamp = packet.timestamp
                pendingRequestSeenAt = System.currentTimeMillis()
            } else if (packet.timestamp == pendingRequestTimestamp) {
                currentPing = System.currentTimeMillis() - pendingRequestSeenAt
                pendingRequestTimestamp = null
            }
        }

        if (packet is PlayerAuthInputPacket) {
            val currentTime = System.currentTimeMillis()

            if (currentTime - lastPacketCountReset >= packetCountInterval) {
                lastPacketCountReset = currentTime
                incomingPackets = 0
                outgoingPackets = 0
            }

            if (currentTime - lastDisplayTime >= displayInterval) {
                lastDisplayTime = currentTime

                val pingText = if (currentPing >= 0) "${currentPing}ms" else "..."

                val networkText = if (colorStyle) {
                    buildString {
                        append("§l§c[Network] §r")
                        append("§fPing: §a$pingText")
                        if (showPacketCounts) {
                            append(" §f| §fPackets: §a↑$outgoingPackets §c↓$incomingPackets")
                        }
                    }
                } else {
                    buildString {
                        append("Network: ")
                        append("Ping: $pingText")
                        if (showPacketCounts) {
                            append(" | Packets: ↑$outgoingPackets ↓$incomingPackets")
                        }
                    }
                }

                ActionBarManager.updateModule("network", networkText)
                ActionBarManager.display(session)
            }
        }
    }

    override fun afterPacketBound(packet: BedrockPacket) {
        if (!isEnabled) return

        outgoingPackets++
    }

    override fun onDisabled() {
        super.onDisabled()
        if (isSessionCreated) {
            ActionBarManager.removeModule("network")
            ActionBarManager.display(session)
        }
    }
}