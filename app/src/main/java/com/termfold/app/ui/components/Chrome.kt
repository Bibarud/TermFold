package com.termfold.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.remember
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.termfold.app.R
import com.termfold.app.ui.theme.Palette
import com.termfold.app.ui.theme.TermFoldIcons

/** Bottom navigation destinations. */
enum class NavTab(val icon: ImageVector, val inactiveIcon: ImageVector, val labelRes: Int) {
    FOLDERS(TermFoldIcons.Home, TermFoldIcons.Home, R.string.cd_home),
    FILES(TermFoldIcons.Files, TermFoldIcons.Files, R.string.cd_files),
    SETTINGS(TermFoldIcons.Gear, TermFoldIcons.Gear, R.string.cd_settings),
}

/** Docked bottom bar for phones: icon over label, the current tab in full white. */
@Composable
fun BottomNav(
    selected: NavTab,
    onSelect: (NavTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().background(Palette.Bg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.BorderSoft))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(62.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavTab.entries.forEach { tab ->
                val active = tab == selected
                val tint by animateColorAsState(
                    targetValue = if (active) Palette.Text else Palette.TextFaint,
                    animationSpec = tween(180),
                    label = "navTint",
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = if (active) tab.icon else tab.inactiveIcon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(tab.labelRes),
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
                        color = tint,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Fixed-height bar reserving space so list content is never hidden behind [BottomNav]. */
@Composable
fun BottomNavSpacer() {
    Spacer(Modifier.height(20.dp))
}





