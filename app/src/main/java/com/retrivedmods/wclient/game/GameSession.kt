package com.retrivedmods.wclient.game

import android.util.Log
import com.retrivedmods.wclient.application.AppContext
import com.retrivedmods.wclient.game.entity.LocalPlayer
import com.retrivedmods.wclient.game.registry.BlockMapping
import com.retrivedmods.wclient.game.registry.BlockMappingProvider
import com.retrivedmods.wclient.game.registry.ItemMapping
import com.retrivedmods.wclient.game.registry.ItemMappingProvider
import com.retrivedmods.wclient.game.world.Level
import com.retrivedmods.wrelay.WRelaySession
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import org.cloudburstmc.protocol.bedrock.packet.ItemComponentPacket
import org.cloudburstmc.protocol.bedrock.packet.StartGamePacket
import org.cloudburstmc.protocol.bedrock.packet.TextPacket
import org.cloudburstmc.protocol.common.SimpleDefinitionRegistry
import java.util.concurrent.ConcurrentHashMap

@Suppress("MemberVisibilityCanBePrivate")
class GameSession(val wRelaySession: WRelaySession) : ComposedPacketHandler {

    val localPlayer = LocalPlayer(this)
    val level = Level(this)

    val protocolVersion: Int
        get() = wRelaySession.server.codec.protocolVersion

    private val mappingProviderContext = AppContext.instance

    private val blockMappingProvider = BlockMappingProvider(mappingProviderContext)
    private val itemMappingProvider = ItemMappingProvider(mappingProviderContext)

    lateinit var blockMapping: BlockMapping
    lateinit var itemMapping: ItemMapping

    private var startGameReceived = false

    // Última vez (nanoTime) que se informó de un fallo, por clave (nombre del
    // módulo, "localPlayer", "level"...). Un módulo que falla en
    // PlayerAuthInputPacket lo hace ~20 veces por segundo; sin límite, cada fallo
    // enviaba un TextPacket al jugador y volcaba un stack trace a logcat: una
    // tormenta de mensajes que empeora justo lo que se quería diagnosticar.
    private val lastFailureReportNs = ConcurrentHashMap<String, Long>()

    private fun reportFailure(key: String, packet: BedrockPacket, e: Exception, notifyPlayer: Boolean) {
        val now = System.nanoTime()
        val last = lastFailureReportNs[key]
        if (last != null && now - last < FAILURE_REPORT_INTERVAL_NS) return
        lastFailureReportNs[key] = now

        Log.e("GameSession", "$key failed to handle ${packet::class.simpleName}", e)
        if (notifyPlayer) {
            displayClientMessage("[Lynx Client] $key crashed on ${packet::class.simpleName}: ${e.message}")
        }
    }

    private companion object {
        const val FAILURE_REPORT_INTERVAL_NS = 10_000_000_000L
    }

    fun clientBound(packet: BedrockPacket) {
        wRelaySession.clientBound(packet)
    }

    fun serverBound(packet: BedrockPacket) {
        wRelaySession.serverBound(packet)
    }

    // ComposedPacketHandler fusiona los dos sentidos en beforePacketBound() y se
    // pierde de dónde viene el paquete. Se sobrescriben los puntos de entrada
    // reales del relay para conservarlo. OJO con los nombres: el relay llama
    // beforeClientBound() para lo que ENVÍA EL JUEGO (ServerSession.onPacket) y
    // beforeServerBound() para lo que ENVÍA EL SERVIDOR (ClientSession.onPacket).
    override fun beforePacketBound(packet: BedrockPacket): Boolean =
        process(packet, fromServer = false)

    override fun beforeClientBound(packet: BedrockPacket): Boolean =
        process(packet, fromServer = false)

    override fun beforeServerBound(packet: BedrockPacket): Boolean =
        process(packet, fromServer = true)

    private fun process(packet: BedrockPacket, fromServer: Boolean): Boolean {
        if (!PacketDiagnostics.enabled) return processPacket(packet, fromServer, false)

        val start = System.nanoTime()
        try {
            return processPacket(packet, fromServer, true)
        } finally {
            PacketDiagnostics.recordPacket(packet, System.nanoTime() - start)
        }
    }

    private fun processPacket(packet: BedrockPacket, fromServer: Boolean, diag: Boolean): Boolean {
        when (packet) {
            is StartGamePacket -> {
                try {
                    val itemDefinitions = SimpleDefinitionRegistry.builder<ItemDefinition>()
                        .addAll(packet.itemDefinitions)
                        .build()

                    wRelaySession.server.peer.codecHelper.itemDefinitions = itemDefinitions
                    wRelaySession.client?.peer?.codecHelper?.itemDefinitions = itemDefinitions

                    Log.i("GameSession", "Successfully set up codecHelper itemDefinitions: ${packet.itemDefinitions.size} items")
                } catch (e: Exception) {
                    Log.e("GameSession", "Failed to set up codecHelper itemDefinitions", e)
                }

                if (!startGameReceived) {
                    startGameReceived = true
                    Log.i("GameSession", "StartGamePacket received")

                    try {
                        blockMapping = blockMappingProvider.craftMapping(protocolVersion)
                        itemMapping = itemMappingProvider.craftMapping(protocolVersion)

                        Log.i("GameSession", "Loaded mappings for protocol $protocolVersion")
                    } catch (e: Exception) {
                        Log.e("GameSession", "Failed to load mappings for protocol $protocolVersion", e)
                    }
                }
            }

            is ItemComponentPacket -> {
                try {
                    val itemDefinitions = SimpleDefinitionRegistry.builder<ItemDefinition>()
                        .addAll(packet.items)
                        .build()

                    wRelaySession.server.peer.codecHelper.itemDefinitions = itemDefinitions
                    wRelaySession.client?.peer?.codecHelper?.itemDefinitions = itemDefinitions

                    Log.i("GameSession", "Successfully updated codecHelper from ItemComponentPacket: ${packet.items.size} items")
                } catch (e: Exception) {
                    Log.e("GameSession", "Failed to update codecHelper from ItemComponentPacket", e)
                }
            }
        }

        // Separados a propósito: antes compartían el mismo try, así que si
        // localPlayer lanzaba (inventario, efectos...) level.onPacketBound no se
        // ejecutaba para ese paquete y el mapa de entidades quedaba sin actualizar.
        try {
            localPlayer.onPacketBound(packet)
        } catch (e: Exception) {
            reportFailure("localPlayer", packet, e, notifyPlayer = false)
        }
        try {
            level.onPacketBound(packet)
        } catch (e: Exception) {
            reportFailure("level", packet, e, notifyPlayer = false)
        }

        val interceptablePacket = InterceptablePacket(packet, fromServer)

        for (module in ModuleManager.modules) {
            // Set session if not already set
            if (!module.isSessionCreated) {
                module.session = this
            }
            val moduleStart = if (diag) System.nanoTime() else 0L
            try {
                module.beforePacketBound(interceptablePacket)
            } catch (e: Exception) {
                reportFailure("Module ${module.name}", packet, e, notifyPlayer = true)
            }
            if (diag) PacketDiagnostics.recordModule(module.name, System.nanoTime() - moduleStart)
            if (interceptablePacket.isIntercepted) {
                return true
            }
        }

        return false
    }

    override fun afterPacketBound(packet: BedrockPacket) {
        for (module in ModuleManager.modules) {
            try {
                module.afterPacketBound(packet)
            } catch (e: Exception) {
                reportFailure("Module ${module.name} (afterPacketBound)", packet, e, notifyPlayer = false)
            }
        }
    }

    override fun onDisconnect(reason: String) {
        localPlayer.onDisconnect()
        level.onDisconnect()
        startGameReceived = false

        for (module in ModuleManager.modules) {
            // Un módulo que lance aquí no debe impedir que los siguientes
            // limpien su estado (overlays, flags de habilidades, jobs...).
            try {
                module.onDisconnect(reason)
            } catch (e: Exception) {
                Log.e("GameSession", "Module ${module.name} failed in onDisconnect", e)
            }
        }
    }

    fun displayClientMessage(message: String, type: TextPacket.Type = TextPacket.Type.RAW) {
        val textPacket = TextPacket()
        textPacket.type = type
        textPacket.sourceName = ""
        textPacket.message = message
        textPacket.xuid = ""
        textPacket.platformChatId = ""
        textPacket.filteredMessage = ""
        clientBound(textPacket)
    }

}