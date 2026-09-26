package com.termfold.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termfold.app.ui.theme.Palette

/** Layout breakpoints, using the standard compact / medium / expanded split. */
enum class WindowWidth { COMPACT, MEDIUM, EXPANDED }

/** True once a layout is wide enough to trade the bottom bar for a rail. */
val WindowWidth.isWide: Boolean get() = this != WindowWidth.COMPACT

@Composable
fun currentWindowWidth(): WindowWidth =
    when (LocalConfiguration.current.screenWidthDp) {
        in 0..599 -> WindowWidth.COMPACT
        in 600..839 -> WindowWidth.MEDIUM
        else -> WindowWidth.EXPANDED
    }

private val RailWidth = 84.dp

/**
 * Vertical navigation rail, used instead of [BottomNav] on wide layouts.
 *
 * Sits flush against the leading edge and takes the start-side insets itself, so a landscape
 * cutout or a side-mounted navigation bar never overlaps the icons.
 */
@Composable
fun NavRail(
    selected: NavTab,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            // Insets are applied before the fixed width, not after: the rail keeps its full width
            // for the icons no matter how wide the landscape cutout or side bar is. Padding after
            // the width shrinks the centred content sideways and can push an icon onto the
            // separator along the rail's right edge, where it gets clipped in half.
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Start + WindowInsetsSides.Vertical
                )
            )
            .width(RailWidth)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRect(
                    color = Palette.BorderSoft,
                    topLeft = Offset(size.width - stroke, 0f),
                    size = Size(stroke, size.height),
                )
            }
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrandMark(size = 30.dp)

        Spacer(Modifier.height(32.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NavTab.entries.forEach { tab ->
                RailItem(
                    tab = tab,
                    active = tab == selected,
                    onClick = { onSelect(tab) },
                )
            }
        }
    }
}

@Composable
private fun RailItem(tab: NavTab, active: Boolean, onClick: () -> Unit) {
    val background by animateColorAsState(
        targetValue = if (active) Palette.CardPressed else Color.Transparent,
        animationSpec = tween(180),
        label = "railBackground",
    )
    val tint by animateColorAsState(
        targetValue = if (active) Palette.Text else Palette.TextFaint,
        animationSpec = tween(180),
        label = "railTint",
    )
    Column(
        modifier = Modifier
            .width(68.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = if (active) tab.icon else tab.inactiveIcon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = stringResource(tab.labelRes),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
            color = tint,
            maxLines = 1,
        )
    }
}
