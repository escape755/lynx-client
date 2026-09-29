package com.retrivedmods.wclient.game

import android.os.Handler
import android.os.Looper
import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Medidor OPCIONAL, apagado por defecto. Existe para poder comprobar en el
 * propio dispositivo (sin adb) si hay o no una caída de rendimiento real cuando
 * hay varios jugadores cerca, en vez de suponerla.
 *
 *   .diag on      empieza a medir (reinicia contadores)
 *   .diag report  muestra el resumen en el chat
 *   .diag off     deja de medir
 *
 * Mide:
 *  - paquetes por segundo y los tipos de paquete más frecuentes;
 *  - cuánto tarda GameSession en procesar cada paquete (media y máximo) - ese
 *    tiempo se gasta dentro del hilo de red, así que retrasa el reenvío;
 *  - qué módulos consumen más tiempo por paquete;
 *  - retraso de la cola del hilo PRINCIPAL de Android (se encola una tarea cada
 *    250 ms y se mide cuánto tarda en ejecutarse). Si ese retraso sube cuando
 *    hay jugadores cerca, la UI (overlays, ClickGUI) está saturada.
 *
 * Con `enabled == false` el coste en el camino caliente es una lectura de un
 * campo volátil por paquete.
 */
object PacketDiagnostics {

    @Volatile
    var enabled = false
        private set

    private class Stat {
        val count = AtomicLong()
        val totalNs = AtomicLong()
        val maxNs = AtomicLong()

        fun add(ns: Long) {
            count.incrementAndGet()
            totalNs.addAndGet(ns)
            maxNs.updateAndGet { current -> if (ns > current) ns else current }
        }

        fun clear() {
            count.set(0)
            totalNs.set(0)
            maxNs.set(0)
        }

        fun avgUs(): Double {
            val n = count.get()
            return if (n == 0L) 0.0 else totalNs.get() / 1000.0 / n
        }

        fun maxMs(): Double = maxNs.get() / 1_000_000.0
    }

    private val packetTypes = ConcurrentHashMap<String, AtomicLong>()
    private val moduleStats = ConcurrentHashMap<String, Stat>()
    private val handlerStat = Stat()
    private val mainLagStat = Stat()

    @Volatile
    private var startedAtNs = System.nanoTime()

    @Volatile
    private var lastProbeNs = 0L

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    fun start() {
        clear()
        enabled = true
    }

    fun stop() {
        enabled = false
    }

    private fun clear() {
        packetTypes.clear()
        moduleStats.clear()
        handlerStat.clear()
        mainLagStat.clear()
        startedAtNs = System.nanoTime()
        lastProbeNs = 0L
    }

    /** Llamado por GameSession por cada paquete mientras `enabled`. */
    fun recordPacket(packet: BedrockPacket, handlerNs: Long) {
        packetTypes.getOrPut(packet.javaClass.simpleName) { AtomicLong() }.incrementAndGet()
        handlerStat.add(handlerNs)
        probeMainThread()
    }

    /** Llamado por GameSession por cada módulo y paquete mientras `enabled`. */
    fun recordModule(moduleName: String, ns: Long) {
        moduleStats.getOrPut(moduleName) { Stat() }.add(ns)
    }

    private fun probeMainThread() {
        val now = System.nanoTime()
        if (now - lastProbeNs < 250_000_000L) return
        lastProbeNs = now
        mainHandler.post { mainLagStat.add(System.nanoTime() - now) }
    }

    fun report(): List<String> {
        val seconds = ((System.nanoTime() - startedAtNs) / 1_000_000_000.0).coerceAtLeast(0.001)
        val totalPackets = handlerStat.count.get()
        val lines = ArrayList<String>()

        lines.add(
            "§b[Diag] " + fmt(seconds) + "s | " + (totalPackets / seconds).toInt() +
                " pkt/s | proceso por paquete: media " + fmt(handlerStat.avgUs()) +
                "us, max " + fmt(handlerStat.maxMs()) + "ms"
        )

        val topPackets = packetTypes.entries
            .sortedByDescending { it.value.get() }
            .take(5)
            .joinToString(", ") { it.key.removeSuffix("Packet") + " " + (it.value.get() / seconds).toInt() + "/s" }
        lines.add("§b[Diag] paquetes: " + topPackets.ifEmpty { "-" })

        val perPacket = totalPackets.coerceAtLeast(1L).toDouble()
        val topModules = moduleStats.entries
            .sortedByDescending { it.value.totalNs.get() }
            .take(5)
            .joinToString(", ") {
                it.key + " " + fmt(it.value.totalNs.get() / 1000.0 / perPacket) + "us (max " +
                    fmt(it.value.maxMs()) + "ms)"
            }
        lines.add("§b[Diag] modulos (us/paquete): " + topModules.ifEmpty { "-" })

        lines.add(
            "§b[Diag] cola hilo principal: media " + fmt(mainLagStat.avgUs() / 1000.0) +
                "ms, max " + fmt(mainLagStat.maxMs()) + "ms (n=" + mainLagStat.count.get() + ")"
        )
        return lines
    }

    private fun fmt(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)
}
