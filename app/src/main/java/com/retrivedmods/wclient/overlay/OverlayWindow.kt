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
        val params = layoutParams
        params.flags = if (focusable) {
            params.flags and LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or LayoutParams.FLAG_NOT_FOCUSABLE
        }
        // FLAG_NOT_FOCUSABLE solo no alcanza: sin esto, Android puede seguir
        // sin animarse a mostrar/adjuntar el teclado a una ventana de tipo
        // overlay aunque ya sea focusable.
        params.softInputMode = if (focusable) {
            LayoutParams.SOFT_INPUT_STATE_VISIBLE or LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        } else {
            LayoutParams.SOFT_INPUT_STATE_UNCHANGED
        }
        runCatching { windowManager.updateViewLayout(composeView, params) }
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