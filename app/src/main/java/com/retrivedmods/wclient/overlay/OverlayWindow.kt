package com.retrivedmods.wclient.overlay

import android.app.Service
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.platform.AndroidUiDispatcher
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope

@Suppress("MemberVisibilityCanBePrivate")
abstract class OverlayWindow {

    open val layoutParams by lazy {
        LayoutParams().apply {
            width = LayoutParams.WRAP_CONTENT
            height = LayoutParams.WRAP_CONTENT
            gravity = Gravity.START or Gravity.TOP
            x = 0
            y = 0
            type = LayoutParams.TYPE_APPLICATION_OVERLAY
            // TYPE_APPLICATION_OVERLAY windows are NOT hardware-accelerated by
            // default - without this flag every overlay (ClickGUI, HUD, all of
            // it) draws through the slow software/CPU path instead of the GPU,
            // which is the single biggest lever for how laggy the whole UI feels.
            flags = LayoutParams.FLAG_NOT_FOCUSABLE or
                LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                LayoutParams.FLAG_HARDWARE_ACCELERATED
            format = PixelFormat.TRANSLUCENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                alpha =
                    (OverlayManager.currentContext!!.getSystemService(Service.INPUT_SERVICE) as? InputManager)?.maximumObscuringOpacityForTouch
                        ?: 0.8f
            }
        }
    }

    open val composeView by lazy {
        ComposeView(OverlayManager.currentContext!!)
    }

    val windowManager: WindowManager
        get() = OverlayManager.currentContext!!.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /**
     * El overlay arranca SIN foco de teclado (FLAG_NOT_FOCUSABLE) - hace
     * falta para que los toques/arrastres normales del menú no le roben el
     * control al juego todo el tiempo. Pero eso significa que NINGÚN
     * cuadrito de texto de NINGÚN módulo puede recibir lo que se tipea
     * mientras esa bandera esté puesta. Esto la saca temporalmente cuando
     * un campo de texto pide foco, y la vuelve a poner cuando lo suelta.
     */
    fun setFocusable(focusable: Boolean) {
        // Sin cambios reales no hace falta molestar al WindowManager (esto se
        // llama cada vez que un campo de texto entra en composición).
        if (!applyFocusParams(focusable)) return
        runCatching { windowManager.updateViewLayout(composeView, layoutParams) }
    }

    /**
     * Deja los parámetros en su estado inicial (NOT_FOCUSABLE) SIN tocar la
     * ventana. Se usa cuando la ventana se está cerrando: si se cerrara con
     * el foco puesto, la próxima vez que se abra nacería robándole el foco
     * al juego y abriendo el teclado sola.
     */
    fun resetFocusParams() {
        applyFocusParams(false)
    }

    /** @return true si los parámetros cambiaron. */
    private fun applyFocusParams(focusable: Boolean): Boolean {
        val params = layoutParams
        val isFocusableNow = (params.flags and LayoutParams.FLAG_NOT_FOCUSABLE) == 0
        if (isFocusableNow == focusable) return false

        params.flags = if (focusable) {
            // Sin NOT_FOCUSABLE la ventana pasaría a ser "touch modal"; esta
            // bandera conserva que los toques fuera de ella lleguen al juego.
            (params.flags and LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
                LayoutParams.FLAG_NOT_TOUCH_MODAL
        } else {
            params.flags or LayoutParams.FLAG_NOT_FOCUSABLE
        }
        // Sacar FLAG_NOT_FOCUSABLE solo no alcanza: además hay que pedirle a
        // Android que muestre el teclado cuando la ventana gane foco. Se usa
        // ALWAYS_VISIBLE porque VISIBLE depende de que Android lo trate como
        // "navegación hacia adelante", algo que no está garantizado acá.
        params.softInputMode = if (focusable) {
            LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else {
            LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        }
        return true
    }

    val lifecycleOwner = OverlayLifecycleOwner()

    val viewModelStore = ViewModelStore()

    val composeScope: CoroutineScope

    val recomposer: Recomposer

    var firstRun = true

    init {
        lifecycleOwner.performRestore(null)

        val coroutineContext = AndroidUiDispatcher.CurrentThread
        composeScope = CoroutineScope(coroutineContext)
        recomposer = Recomposer(coroutineContext)
    }

    @Composable
    abstract fun Content()

}