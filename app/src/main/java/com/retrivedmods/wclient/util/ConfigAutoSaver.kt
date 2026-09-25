package com.retrivedmods.wclient.util

import com.retrivedmods.wclient.game.ModuleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Antes, la configuración solo se guardaba al desconectar el relay o cerrar la
 * app "prolijamente" (onDestroy/stopRelay/off) - si Android mataba el proceso
 * en segundo plano (algo muy común con apps que corren como overlay/servicio),
 * los cambios hechos durante esa sesión se perdían sin avisar.
 *
 * Este objeto agenda un guardado poco después de cada cambio de valor, y
 * cancela el guardado anterior si llega uno nuevo antes - así arrastrar un
 * slider no escribe a disco en cada tick, pero el valor final SIEMPRE se
 * guarda unos milisegundos después de soltar.
 */
object ConfigAutoSaver {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var pendingSave: Job? = null

    fun scheduleSave() {
        pendingSave?.cancel()
        pendingSave = scope.launch {
            delay(400)
            runCatching { ModuleManager.saveConfig() }
        }
    }
}
