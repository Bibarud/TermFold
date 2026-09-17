package com.termfold.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.termfold.app.R
import com.termfold.app.ui.theme.Palette
import com.termfold.app.ui.theme.TermFoldIcons

/** Bottom navigation destinations. */
enum class NavTab(val icon: ImageVector, val inactiveIcon: ImageVector, val labelRes: Int) {
    FOLDERS(TermFoldIcons.Home, TermFoldIcons.Home, R.string.cd_home),
    SETTINGS(TermFoldIcons.Gear, TermFoldIcons.Gear, R.string.cd_settings),
}

/** Floating rounded bottom bar, matching the inset bar in the design. */
@Composable
fun BottomNav(
    selected: NavTab,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .height(58.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.NavBg),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavTab.entries.forEach { tab ->
                val active = tab == selected
                val pill by animateColorAsState(
                    targetValue = if (active) Palette.AccentSoft else Color.Transparent,
                    animationSpec = tween(200),
                    label = "navPill",
                )
                val tint by animateColorAsState(
                    targetValue = if (active) Palette.Accent else Palette.TextFaint,
                    animationSpec = tween(200),
                    label = "navTint",
                )
                val scale by animateFloatAsState(
                    targetValue = if (active) 1.12f else 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                    label = "navScale",
                )
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(pill)
                        .clickable { onSelect(tab) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (active) tab.icon else tab.inactiveIcon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier
                            .size(23.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            },
                    )
                }
            }
        }
    }
}

/** Fixed-height bar reserving space so list content is never hidden behind [BottomNav]. */
@Composable
fun BottomNavSpacer() {
    Spacer(Modifier.height(82.dp))
}





