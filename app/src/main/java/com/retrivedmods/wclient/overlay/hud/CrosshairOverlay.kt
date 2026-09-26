package com.retrivedmods.wclient.overlay.hud

import com.retrivedmods.wclient.overlay.OverlayWindow
import com.retrivedmods.wclient.overlay.OverlayManager
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.retrivedmods.wclient.game.module.visual.CrosshairModule
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class CrosshairOverlay : OverlayWindow() {

    private val _layoutParams by lazy {
        super.layoutParams.apply {
            flags = flags or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            gravity = Gravity.CENTER
        }
    }

    override val layoutParams: WindowManager.LayoutParams
        get() = _layoutParams

    private var crosshairType by mutableStateOf(CrosshairModule.CrosshairType.CROSS)
    private var size by mutableIntStateOf(10)
    private var thickness by mutableIntStateOf(2)
    private var gap by mutableIntStateOf(3)
    private var colorMode by mutableStateOf(CrosshairModule.ColorMode.STATIC)
    private var staticColor by mutableStateOf(CrosshairModule.StaticColor.WHITE)
    private var rainbowSpeed by mutableFloatStateOf(1.0f)
    private var outline by mutableStateOf(true)
    private var outlineThickness by mutableIntStateOf(1)
    private var dynamicColor by mutableStateOf(false)
    private var hitMarker by mutableStateOf(true)
    private var pulsing by mutableStateOf(false)
    private var pulseSpeed by mutableFloatStateOf(1.0f)
    private var showHitMarker by mutableStateOf(false)

    // Reused every draw instead of allocating a new Path per frame (only the
    // DIAMOND crosshair type needs it; everyone else never touches this).
    private val diamondPath = Path()

    companion object {
        val overlayInstance by lazy { CrosshairOverlay() }
        private var shouldShowOverlay = false

        fun showOverlay() {
            if (shouldShowOverlay) {
                try {
                    OverlayManager.showOverlayWindow(overlayInstance)
                } catch (e: Exception) {}
            }
        }

        fun dismissOverlay() {
            try {
                OverlayManager.dismissOverlayWindow(overlayInstance)
            } catch (e: Exception) {}
        }

        fun setOverlayEnabled(enabled: Boolean) {
            shouldShowOverlay = enabled
            if (enabled) showOverlay() else dismissOverlay()
        }

        fun isOverlayEnabled(): Boolean = shouldShowOverlay

        fun setCrosshairType(type: CrosshairModule.CrosshairType) {
            overlayInstance.crosshairType = type
        }

        fun setSize(s: Int) {
            overlayInstance.size = s
        }

        fun setThickness(t: Int) {
            overlayInstance.thickness = t
        }

        fun setGap(g: Int) {
            overlayInstance.gap = g
        }

        fun setColorMode(mode: CrosshairModule.ColorMode) {
            overlayInstance.colorMode = mode
        }

        fun setStaticColor(color: CrosshairModule.StaticColor) {
            overlayInstance.staticColor = color
        }

        fun setRainbowSpeed(speed: Float) {
            overlayInstance.rainbowSpeed = speed
        }

        fun setOutline(o: Boolean) {
            overlayInstance.outline = o
        }

        fun setOutlineThickness(t: Int) {
            overlayInstance.outlineThickness = t
        }

        fun setDynamicColor(dynamic: Boolean) {
            overlayInstance.dynamicColor = dynamic
        }

        fun setHitMarker(hit: Boolean) {
            overlayInstance.hitMarker = hit
        }

        fun setPulsing(pulse: Boolean) {
            overlayInstance.pulsing = pulse
        }

        fun setPulseSpeed(speed: Float) {
            overlayInstance.pulseSpeed = speed
        }

        fun setShowHitMarker(show: Boolean) {
            overlayInstance.showHitMarker = show
        }
    }

    @Composable
    override fun Content() {
        if (!isOverlayEnabled()) return

        // Was: two local `mutableFloatStateOf` vars with no `remember`, stepped by a
        // manual delay(16) loop. Without `remember` the state never survived a
        // recomposition, so every tick reset it to 0f - the rainbow/pulse never
        // actually moved, it just forced this whole window to redraw at ~60fps
        // forever for nothing (that's what the suppressed lint warning was flagging).
        // rememberInfiniteTransition reproduces the same 0->1 cycles through
        // Compose's real frame clock, at the same speed as the old increments.
        val infiniteTransition = rememberInfiniteTransition(label = "crosshairAnim")
        val rainbowOffset by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = (800f / rainbowSpeed.coerceAtLeast(0.01f)).toInt().coerceAtLeast(1),
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "rainbowOffset"
        )
        val pulseOffset by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = (320f / pulseSpeed.coerceAtLeast(0.01f)).toInt().coerceAtLeast(1),
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "pulseOffset"
        )

        val crosshairColor = getCrosshairColor(rainbowOffset, pulseOffset)
        val currentSize = if (pulsing) {
            size + (sin(pulseOffset * 2 * PI) * 2).toInt()
        } else {
            size
        }

        Box(
            modifier = Modifier.size(100.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(100.dp)) {
                val center = Offset(size.width / 2, size.height / 2)
                
                if (outline) {
                    drawCrosshair(
                        center = center,
                        size = currentSize,
                        thickness = thickness + outlineThickness * 2,
                        gap = gap,
                        color = Color.Black,
                        type = crosshairType
                    )
                }
                
                drawCrosshair(
                    center = center,
                    size = currentSize,
                    thickness = thickness,
                    gap = gap,
                    color = crosshairColor,
                    type = crosshairType
                )
                
                if (showHitMarker && hitMarker) {
                    drawHitMarker(center, currentSize + 5, crosshairColor)
                }
            }
        }
    }

    private fun DrawScope.drawCrosshair(
        center: Offset,
        size: Int,
        thickness: Int,
        gap: Int,
        color: Color,
        type: CrosshairModule.CrosshairType
    ) {
        val strokeWidth = thickness.toFloat()
        val halfSize = size.toFloat()
        val gapSize = gap.toFloat()

        when (type) {
            CrosshairModule.CrosshairType.CROSS -> {
                // Horizontal line
                drawLine(
                    color = color,
                    start = Offset(center.x - halfSize, center.y),
                    end = Offset(center.x - gapSize, center.y),
                    strokeWidth = strokeWidth
                )
                drawLine(
                    color = color,
                    start = Offset(center.x + gapSize, center.y),
                    end = Offset(center.x + halfSize, center.y),
                    strokeWidth = strokeWidth
                )

                drawLine(
                    color = color,
                    start = Offset(center.x, center.y - halfSize),
                    end = Offset(center.x, center.y - gapSize),
                    strokeWidth = strokeWidth
                )
                drawLine(
                    color = color,
                    start = Offset(center.x, center.y + gapSize),
                    end = Offset(center.x, center.y + halfSize),
                    strokeWidth = strokeWidth
                )
            }
            
            CrosshairModule.CrosshairType.DOT -> {
                drawCircle(
                    color = color,
                    radius = thickness.toFloat(),
                    center = center
                )
            }
            
            CrosshairModule.CrosshairType.CIRCLE -> {
                drawCircle(
                    color = color,
                    radius = halfSize,
                    center = center,
                    style = Stroke(width = strokeWidth)
                )
            }
            
            CrosshairModule.CrosshairType.SQUARE -> {
                val topLeft = Offset(center.x - halfSize, center.y - halfSize)
                val size = androidx.compose.ui.geometry.Size(halfSize * 2, halfSize * 2)
                drawRect(
                    color = color,
                    topLeft = topLeft,
                    size = size,
                    style = Stroke(width = strokeWidth)
                )
            }
            
            CrosshairModule.CrosshairType.DIAMOND -> {
                diamondPath.reset()
                diamondPath.moveTo(center.x, center.y - halfSize)
                diamondPath.lineTo(center.x + halfSize, center.y)
                diamondPath.lineTo(center.x, center.y + halfSize)
                diamondPath.lineTo(center.x - halfSize, center.y)
                diamondPath.close()
                drawPath(
                    path = diamondPath,
                    color = color,
                    style = Stroke(width = strokeWidth)
                )
            }
            
            CrosshairModule.CrosshairType.T_SHAPE -> {
                drawLine(
                    color = color,
                    start = Offset(center.x - halfSize, center.y - gapSize),
                    end = Offset(center.x + halfSize, center.y - gapSize),
                    strokeWidth = strokeWidth
                )

                drawLine(
                    color = color,
                    start = Offset(center.x, center.y - gapSize),
                    end = Offset(center.x, center.y + halfSize),
                    strokeWidth = strokeWidth
                )
            }
            
            CrosshairModule.CrosshairType.PLUS -> {
                drawLine(
                    color = color,
                    start = Offset(center.x - halfSize, center.y),
                    end = Offset(center.x + halfSize, center.y),
                    strokeWidth = strokeWidth
                )
                drawLine(
                    color = color,
                    start = Offset(center.x, center.y - halfSize),
                    end = Offset(center.x, center.y + halfSize),
                    strokeWidth = strokeWidth
                )
            }
        }
    }

    private fun DrawScope.drawHitMarker(center: Offset, size: Int, color: Color) {
        val halfSize = size.toFloat()
        val strokeWidth = 3f

        drawLine(
            color = color,
            start = Offset(center.x - halfSize, center.y - halfSize),
            end = Offset(center.x + halfSize, center.y + halfSize),
            strokeWidth = strokeWidth
        )
        drawLine(
            color = color,
            start = Offset(center.x + halfSize, center.y - halfSize),
            end = Offset(center.x - halfSize, center.y + halfSize),
            strokeWidth = strokeWidth
        )
    }

    private fun getCrosshairColor(rainbowOffset: Float, pulseOffset: Float): Color {
        return when (colorMode) {
            CrosshairModule.ColorMode.STATIC -> getStaticColorValue()
            CrosshairModule.ColorMode.RAINBOW -> hsvToRgb(rainbowOffset, 0.8f, 1f)
            CrosshairModule.ColorMode.HEALTH_BASED -> getHealthBasedColor()
            CrosshairModule.ColorMode.TARGET_BASED -> getTargetBasedColor()
            CrosshairModule.ColorMode.PULSING -> {
                val alpha = 0.5f + sin(pulseOffset * 2 * PI).toFloat() * 0.5f
                getStaticColorValue().copy(alpha = alpha)
            }
        }
    }

    private fun getStaticColorValue(): Color {
        return when (staticColor) {
            CrosshairModule.StaticColor.WHITE -> Color.White
            CrosshairModule.StaticColor.RED -> Color.Red
            CrosshairModule.StaticColor.GREEN -> Color.Green
            CrosshairModule.StaticColor.BLUE -> Color.Blue
            CrosshairModule.StaticColor.YELLOW -> Color.Yellow
            CrosshairModule.StaticColor.CYAN -> Color.Cyan
            CrosshairModule.StaticColor.MAGENTA -> Color.Magenta
            CrosshairModule.StaticColor.ORANGE -> Color(1f, 0.5f, 0f)
        }
    }

    private fun getHealthBasedColor(): Color {
        return Color.Green
    }

    private fun getTargetBasedColor(): Color {
        return Color.White
    }

    private fun hsvToRgb(h: Float, s: Float, v: Float): Color {
        val hDegrees = h * 360f
        val c = v * s
        val x = c * (1 - abs((hDegrees / 60f) % 2 - 1))
        val m = v - c

        val (r, g, b) = when {
            hDegrees < 60 -> Triple(c, x, 0f)
            hDegrees < 120 -> Triple(x, c, 0f)
            hDegrees < 180 -> Triple(0f, c, x)
            hDegrees < 240 -> Triple(0f, x, c)
            hDegrees < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }

        return Color(
            red = (r + m).coerceIn(0f, 1f),
            green = (g + m).coerceIn(0f, 1f),
            blue = (b + m).coerceIn(0f, 1f)
        )
    }
}