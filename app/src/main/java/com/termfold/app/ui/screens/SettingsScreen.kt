package com.termfold.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
    // The size is measured in the background; everything else is instant.
    val sizeText by androidx.compose.runtime.produceState(
        initialValue = context.getString(R.string.shell_size_calculating),
        shellState,
    ) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { shellSizeText(context) }
    }
    val status = remember(shellState, sizeText) { shellStatus(context, sizeText) }
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
            item { GroupLabel(stringResource(R.string.settings_background)) }
            item { BackgroundCard() }

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

/**
 * The bubble and the "work finished" notifications. Android keeps the final say over both, so
 * each switch also shows when the system is blocking it, with a shortcut to the setting.
 */
@Composable
private fun BackgroundCard() {
    val context = LocalContext.current
    val notifier = com.termfold.app.notify.Notifier
    var bubbles by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(notifier.bubblesEnabled(context)) }
    var notify by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(notifier.notificationsEnabled(context)) }
    // Re-read what Android allows whenever the user comes back from its settings.
    var bubblesAllowed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(notifier.bubblesAllowed(context)) }
    var canNotify by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(notifier.canNotify(context)) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                bubblesAllowed = notifier.bubblesAllowed(context)
                canNotify = notifier.canNotify(context)
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val bubblesSupported = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Palette.Card)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    ) {
        if (bubblesSupported) {
            SwitchRow(
                title = stringResource(R.string.settings_bubble),
                body = stringResource(R.string.settings_bubble_desc),
                checked = bubbles,
                onChange = { bubbles = it; notifier.setBubblesEnabled(context, it) },
                warning = if (bubbles && (!bubblesAllowed || !canNotify)) stringResource(R.string.settings_bubble_blocked) else null,
                actionLabel = stringResource(R.string.settings_bubble_allow),
                onAction = {
                    runCatching {
                        context.startActivity(
                            if (!canNotify) notificationSettings(context) else notifier.bubbleSettingsIntent(context),
                        )
                    }
                },
            )
            HairlineDivider()
        }
        SwitchRow(
            title = stringResource(R.string.settings_notify),
            body = stringResource(R.string.settings_notify_desc),
            checked = notify,
            onChange = { notify = it; notifier.setNotificationsEnabled(context, it) },
            warning = if (notify && !canNotify) stringResource(R.string.settings_notify_blocked) else null,
            actionLabel = stringResource(R.string.settings_notify_allow),
            onAction = { runCatching { context.startActivity(notificationSettings(context)) } },
        )
    }
}

private fun notificationSettings(context: android.content.Context) =
    android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

@Composable
private fun SwitchRow(
    title: String,
    body: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    warning: String?,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.Text)
                Spacer(Modifier.height(2.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            }
            Spacer(Modifier.size(12.dp))
            androidx.compose.material3.Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                    checkedTrackColor = Palette.Accent,
                    checkedBorderColor = Palette.Accent,
                    uncheckedThumbColor = Palette.TextDim,
                    uncheckedTrackColor = Palette.Bg,
                    uncheckedBorderColor = Palette.Border,
                ),
            )
        }
        if (warning != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Yellow,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Palette.Accent.copy(alpha = 0.22f))
                        .clickable(onClick = onAction)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
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
