package com.retrivedmods.wclient.game

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import com.retrivedmods.wclient.R
import com.retrivedmods.wclient.overlay.OverlayManager
import com.retrivedmods.wclient.util.translatedSelf
import kotlin.math.roundToInt


private val DarkBackground = Color(0xFFFFFFFF)
private val CardBackground = Color(0xFF1A0805)
private val CardBackgroundExpanded = Color(0xFF260C06)
private val AccentPrimary = Color(0xFFFF7A00)
private val AccentSecondary = Color(0xFFE63946)
private val AccentDark = Color(0xFFB8290A)
private val TextPrimary = Color(0xFFFFEEDD)
private val TextSecondary = Color(0xFFC9A08C)
private val BorderColor = Color(0xFF8C3010)
private val BorderColorActive = Color(0xFFB8451A)
private val ErrorRed = Color(0xFFCF222E)

private val moduleCache = HashMap<ModuleCategory, List<Module>>()

private fun fetchCachedModules(category: ModuleCategory): List<Module> =
    moduleCache.getOrPut(category) {
        ModuleManager.modules.filter { !it.private && it.category === category }
    }

@Composable
fun ModuleContent(moduleCategory: ModuleCategory) {
    // Filtering an already-in-memory list is sub-millisecond work - no need for
    // a coroutine/IO dispatch or a loading spinner here, that was adding a
    // visible stall to every single category switch (the most frequent action
    // in this menu).
    val list = remember(moduleCategory) { fetchCachedModules(moduleCategory) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(list.size, key = { list[it].name }) { i -> ModuleCard(list[i]) }
    }
}

@Composable
private fun ModuleCard(module: Module) {
    val values = module.values
    val bg by animateColorAsState(
        targetValue = if (module.isExpanded) CardBackgroundExpanded else CardBackground,
        animationSpec = tween(durationMillis = 300),
        label = "moduleBg"
    )

    val elevation by animateFloatAsState(
        targetValue = if (module.isExpanded) 4f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "cardElevation"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { module.isExpanded = !module.isExpanded },
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = bg),
        elevation = CardDefaults.cardElevation(defaultElevation = elevation.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    )
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    module.name.translatedSelf,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (module.isEnabled) AccentPrimary else TextPrimary
                )
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = module.isEnabled,
                    onCheckedChange = { module.isEnabled = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = AccentPrimary,
                        checkedTrackColor = AccentPrimary.copy(alpha = 0.3f),
                        checkedBorderColor = Color.Transparent,
                        uncheckedThumbColor = Color(0xFF8C4020),
                        uncheckedTrackColor = Color(0xFF33120A),
                        uncheckedBorderColor = BorderColor
                    ),
                    modifier = Modifier
                        .width(52.dp)
                        .height(32.dp)
                )
            }

            if (module.isExpanded) {
                values.fastForEach {
                    when (it) {
                        is BoolValue -> BoolValueContent(it)
                        is FloatValue -> FloatValueContent(it)
                        is IntValue -> IntValueContent(it)
                        is ListValue -> ChoiceValueContent(it)
                        is EnumValue<*> -> EnumValueContent(it)
                        is StringValue -> StringValueContent(it)
                    }
                }
                ShortcutContent(module)
            }
        }
    }
}

@Composable
private fun ChoiceValueContent(value: ListValue) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        Text(
            value.name.translatedSelf,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            value.listItems.forEach { item ->
                ElevatedFilterChip(
                    selected = value.value == item,
                    onClick = { if (value.value != item) value.value = item },
                    label = { Text(item.name.translatedSelf) },
                    modifier = Modifier.height(32.dp),
                    enabled = true,
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color(0xFF33120A),
                        selectedContainerColor = AccentPrimary,
                        labelColor = TextSecondary,
                        selectedLabelColor = Color.White,
                        disabledContainerColor = Color(0xFF1A0805),
                        disabledLabelColor = Color(0xFFA6705A)
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = value.value == item,
                        borderColor = BorderColor,
                        selectedBorderColor = AccentPrimary,
                        disabledBorderColor = Color(0xFF33120A),
                        disabledSelectedBorderColor = Color(0xFF33120A)
                    )
                )
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

@Composable
private fun FloatValueContent(value: FloatValue) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp)) {
        val range = value.range
        val fraction = ((value.value - range.start) / (range.endInclusive - range.start))
            .let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) }

        ImGuiSlider(
            fraction = fraction,
            displayText = "${value.name.translatedSelf}: ${String.format("%.2f", value.value)}",
            onFractionChange = { frac ->
                val raw = range.start + frac * (range.endInclusive - range.start)
                val rounded = ((raw * 100.0).roundToInt() / 100.0).toFloat().coerceIn(range.start, range.endInclusive)
                if (value.value != rounded) value.value = rounded
            }
        )
    }
}

/**
 * ImGui-style slider: the fill bar itself is the control, with the name and
 * current value overlaid centered on top of it, instead of a separate label
 * row plus a Material thumb-and-track Slider. Tapping or dragging anywhere on
 * the bar jumps/tracks the value directly - no animateFloatAsState wrapping
 * the position, since that's what made the old sliders lag behind the finger.
 */
@Composable
private fun ImGuiSlider(
    fraction: Float,
    displayText: String,
    onFractionChange: (Float) -> Unit
) {
    var trackWidthPx by remember { mutableStateOf(1f) }

    fun updateFromX(x: Float) {
        onFractionChange((x / trackWidthPx).coerceIn(0f, 1f))
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(26.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF1A0805))
            .border(1.dp, BorderColor, RoundedCornerShape(4.dp))
            .onGloballyPositioned { trackWidthPx = it.size.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset -> updateFromX(offset.x) }
                ) { change, _ ->
                    updateFromX(change.position.x)
                }
            }
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(AccentPrimary, RoundedCornerShape(4.dp))
        )
        Text(
            text = displayText,
            color = TextPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 6.dp)
        )
    }
}

@Composable
private fun IntValueContent(value: IntValue) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp)) {
        val start = value.range.first.toFloat()
        val end = value.range.last.toFloat()
        val fraction = ((value.value - start) / (end - start))
            .let { if (it.isNaN()) 0f else it.coerceIn(0f, 1f) }

        ImGuiSlider(
            fraction = fraction,
            displayText = "${value.name.translatedSelf}: ${value.value}",
            onFractionChange = { frac ->
                val next = (start + frac * (end - start)).roundToInt().coerceIn(value.range.first, value.range.last)
                if (value.value != next) value.value = next
            }
        )
    }
}

@Composable
private fun BoolValueContent(value: BoolValue) {
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
            .fillMaxWidth()
            .toggleable(
                value = value.value,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = true
            ) { value.value = it },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            value.name.translatedSelf,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Spacer(Modifier.weight(1f))
        Checkbox(
            checked = value.value,
            onCheckedChange = null,
            modifier = Modifier.padding(0.dp),
            enabled = true,
            colors = CheckboxDefaults.colors(
                uncheckedColor = Color(0xFF8C4020),
                checkedColor = AccentPrimary,
                checkmarkColor = Color.White,
                disabledCheckedColor = Color(0xFF8C4020),
                disabledUncheckedColor = Color(0xFF3D1D10),
                disabledIndeterminateColor = Color(0xFF8C4020)
            )
        )
    }
}

@Composable
private fun ShortcutContent(module: Module) {
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .fillMaxWidth()
            .toggleable(
                value = module.isShortcutDisplayed,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = true
            ) {
                module.isShortcutDisplayed = it
                if (it) OverlayManager.showOverlayWindow(module.overlayShortcutButton)
                else OverlayManager.dismissOverlayWindow(module.overlayShortcutButton)
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.shortcut),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        Spacer(Modifier.weight(1f))
        Checkbox(
            checked = module.isShortcutDisplayed,
            onCheckedChange = null,
            modifier = Modifier.padding(0.dp),
            enabled = true,
            colors = CheckboxDefaults.colors(
                uncheckedColor = Color(0xFF8C4020),
                checkedColor = AccentPrimary,
                checkmarkColor = Color.White,
                disabledCheckedColor = Color(0xFF8C4020),
                disabledUncheckedColor = Color(0xFF3D1D10),
                disabledIndeterminateColor = Color(0xFF8C4020)
            )
        )
    }
}

@Composable
private fun <T : Enum<T>> EnumValueContent(value: EnumValue<T>) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        Text(
            value.name.translatedSelf,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            value.enumClass.enumConstants?.forEach { option ->
                ElevatedFilterChip(
                    selected = value.value == option,
                    onClick = { if (value.value != option) value.value = option },
                    label = { Text(option.name.translatedSelf) },
                    modifier = Modifier.height(32.dp),
                    enabled = true,
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = Color(0xFF33120A),
                        selectedContainerColor = AccentPrimary,
                        labelColor = TextSecondary,
                        selectedLabelColor = Color.White,
                        disabledContainerColor = Color(0xFF1A0805),
                        disabledLabelColor = Color(0xFFA6705A)
                    ),
                    border = FilterChipDefaults.filterChipBorder(
                        enabled = true,
                        selected = value.value == option,
                        borderColor = BorderColor,
                        selectedBorderColor = AccentPrimary,
                        disabledBorderColor = Color(0xFF33120A),
                        disabledSelectedBorderColor = Color(0xFF33120A)
                    )
                )
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

@Composable
private fun StringValueContent(value: StringValue) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
        Text(
            value.name.translatedSelf,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        OutlinedTextField(
            value = value.value,
            onValueChange = { value.value = it },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { state ->
                    com.retrivedmods.wclient.overlay.OverlayManager.setClickGuiFocusable(state.isFocused)
                },
            enabled = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = AccentPrimary,
                unfocusedBorderColor = BorderColor,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = AccentPrimary,
                disabledBorderColor = Color(0xFF33120A),
                disabledTextColor = Color(0xFFA6705A),
                errorBorderColor = ErrorRed,
                errorTextColor = TextPrimary,
                errorCursorColor = ErrorRed
            ),
            shape = MaterialTheme.shapes.small
        )
    }
}

private fun IntRange.toFloatRange() = first.toFloat()..last.toFloat()