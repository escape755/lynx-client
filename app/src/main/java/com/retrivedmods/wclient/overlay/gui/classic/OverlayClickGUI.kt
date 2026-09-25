package com.retrivedmods.wclient.overlay.gui.classic

import android.content.Intent
import android.net.Uri
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrivedmods.wclient.R
import com.retrivedmods.wclient.game.ModuleCategory
import com.retrivedmods.wclient.game.ModuleContent
import com.retrivedmods.wclient.overlay.OverlayManager
import com.retrivedmods.wclient.overlay.OverlayWindow

private val DarkBackground = Color(0xFF0D0403)
private val ContentBackground = Color(0xFF170805)
private val SidebarBackground = Color(0xFF170805)
private val AccentPrimary = Color(0xFFFF7A00)
private val TextPrimary = Color(0xFFFFEEDD)
private val TextSecondary = Color(0xFFC9A08C)
private val ButtonBackground = Color(0xFF2B0E08)

class OverlayClickGUI : OverlayWindow() {

    private val _layoutParams by lazy {
        super.layoutParams.apply {
            flags = flags or WindowManager.LayoutParams.FLAG_DIM_BEHIND
            // Blur-behind was removed on purpose: it's a real-time compositor
            // effect recalculated every frame on top of the game still rendering
            // underneath, and is one of the more common causes of overlay jank
            // on Android. Dropping it is the single biggest fluidity win here.
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            dimAmount = 0.55f
            windowAnimations = android.R.style.Animation_Dialog
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }
    }

    override val layoutParams: WindowManager.LayoutParams
        get() = _layoutParams

    private var selectedModuleCategory by mutableStateOf(ModuleCategory.Combat)

    @OptIn(ExperimentalAnimationApi::class)
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val snackbarHostState = remember { SnackbarHostState() }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0xD0000000))
                .clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                ) {
                    OverlayManager.dismissOverlayWindow(this)
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(600.dp)
                    .height(340.dp)
                    .background(DarkBackground, RoundedCornerShape(6.dp))
                    .border(1.dp, AccentPrimary.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                    .clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    ) {}
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    HeaderBar(
                        onDiscord = {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://discord.gg/N2Gejr8Fbp")
                                )
                            )
                        },
                        onWebsite = {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://wclient.neocities.org/")
                                )
                            )
                        },
                        onClose = { OverlayManager.dismissOverlayWindow(this@OverlayClickGUI) }
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(AccentPrimary.copy(alpha = 0.35f))
                    )
                    MainArea(snackbarHostState)
                }

                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                )
            }
        }
    }

    @Composable
    private fun HeaderBar(
        onDiscord: () -> Unit,
        onWebsite: () -> Unit,
        onClose: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(DarkBackground)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Lynx Client",
                    color = AccentPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                IconButton(
                    onClick = onDiscord,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_discord),
                        contentDescription = "Discord",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(
                    onClick = onWebsite,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_web),
                        contentDescription = "Website",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalAnimationApi::class)
    @Composable
    private fun MainArea(snackbarHostState: SnackbarHostState) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CategorySidebar()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ContentBackground, RoundedCornerShape(4.dp))
                    .border(1.dp, AccentPrimary.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                    .padding(14.dp)
            ) {
                AnimatedContent(
                    targetState = selectedModuleCategory,
                    transitionSpec = {
                        fadeIn(tween(120)) togetherWith fadeOut(tween(120))
                    },
                    label = "CategoryContent"
                ) { category ->
                    if (category == ModuleCategory.Config) {
                        ConfigurationScreen(snackbarHostState = snackbarHostState)
                    } else {
                        ModuleContent(category)
                    }
                }
            }
        }
    }

    @Composable
    private fun CategorySidebar() {
        val categories = remember { ModuleCategory.entries }

        LazyColumn(
            modifier = Modifier
                .width(120.dp)
                .fillMaxHeight()
                .background(SidebarBackground, RoundedCornerShape(4.dp))
                .border(1.dp, AccentPrimary.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            items(categories.size) { index ->
                val category = categories[index]
                CategoryTab(
                    category = category,
                    isSelected = selectedModuleCategory == category,
                    onClick = { selectedModuleCategory = category }
                )
            }
        }
    }

    @Composable
    private fun CategoryTab(
        category: ModuleCategory,
        isSelected: Boolean,
        onClick: () -> Unit
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .background(if (isSelected) ButtonBackground else Color.Transparent)
                .clickable { onClick() }
                .padding(horizontal = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight(0.6f)
                    .background(if (isSelected) AccentPrimary else Color.Transparent)
            )
            Icon(
                painter = painterResource(category.iconResId),
                contentDescription = category.name,
                tint = if (isSelected) AccentPrimary else TextSecondary,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = category.name,
                color = if (isSelected) AccentPrimary else TextSecondary,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}