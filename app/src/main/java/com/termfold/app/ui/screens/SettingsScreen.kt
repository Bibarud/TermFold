package com.termfold.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.termfold.app.BuildConfig
import com.termfold.app.R
import com.termfold.app.core.ShellState
import com.termfold.app.ui.components.BottomNavSpacer
import com.termfold.app.ui.components.GroupLabel
import com.termfold.app.ui.components.StatusDot
import com.termfold.app.ui.theme.Mono
import com.termfold.app.ui.theme.Palette

/** Settings tab: the state of the bundled Linux environment and app information. */
@Composable
fun SettingsScreen(
    shellState: ShellState,
    folderCount: Int,
    sessionCount: Int,
    onRepairShell: () -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
) {
    val context = LocalContext.current
    val status = remember(shellState) { shellStatus(context) }
    val edge = if (wide) 30.dp else 22.dp

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.titleLarge,
            color = Palette.Text,
            modifier = Modifier.padding(start = edge, end = edge, top = 26.dp),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = if (wide) 30.dp else 16.dp,
                end = if (wide) 26.dp else 16.dp,
                top = 18.dp,
                bottom = 18.dp,
            ),
        ) {
            item { GroupLabel(stringResource(R.string.shell_title)) }

            item {
                ShellCard(
                    status = status,
                    shellState = shellState,
                    onRepairShell = onRepairShell,
                )
            }

            item { Spacer(Modifier.height(20.dp)) }
            item { GroupLabel(stringResource(R.string.about_title)) }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Palette.Card)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    InfoLine(
                        label = stringResource(R.string.folders_title),
                        value = "$folderCount",
                    )
                    HairlineDivider()
                    InfoLine(
                        label = stringResource(R.string.sessions_header),
                        value = "$sessionCount",
                    )
                    HairlineDivider()
                    InfoLine(
                        label = stringResource(R.string.version_label),
                        value = BuildConfig.VERSION_NAME,
                        mono = true,
                    )
                }
            }

            item { Spacer(Modifier.height(20.dp)) }

            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Palette.Card)
                        .padding(18.dp),
                ) {
                    Text(
                        text = stringResource(R.string.about_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.TextDim,
                    )
                }
            }

            item { BottomNavSpacer() }
        }
    }
}

/** What the app knows about the bundled Ubuntu environment. */
@Composable
private fun ShellCard(
    status: ShellStatus,
    shellState: ShellState,
    onRepairShell: () -> Unit,
) {
    val ready = shellState == ShellState.READY && status.ready
    val unsupported = shellState == ShellState.UNSUPPORTED || !status.supported

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.Card)
            .padding(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                when {
                    unsupported -> Palette.Pink
                    ready -> Palette.Green
                    else -> Palette.Yellow
                }
            )
            Spacer(Modifier.size(10.dp))
            Text(
                text = stringResource(
                    if (ready) R.string.shell_ready else R.string.shell_not_ready
                ),
                style = MaterialTheme.typography.titleMedium,
                color = Palette.Text,
            )
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.shell_body),
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextDim,
        )

        if (unsupported) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.shell_unsupported),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.Pink,
            )
        }

        Spacer(Modifier.height(14.dp))

        InfoLine(label = stringResource(R.string.version_label), value = status.flavour, mono = true)
        HairlineDivider()
        InfoLine(
            label = stringResource(R.string.shell_arch_label),
            value = status.architecture,
            mono = true,
        )
        HairlineDivider()
        InfoLine(
            label = stringResource(R.string.shell_size_label),
            value = status.sizeText,
            mono = true,
        )

        if (!unsupported && !ready) {
            Spacer(Modifier.height(16.dp))
            ActionRow(
                items = listOf(stringResource(R.string.action_repair) to onRepairShell)
            )
        }
    }
}
