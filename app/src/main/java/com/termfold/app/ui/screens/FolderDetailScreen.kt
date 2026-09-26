package com.termfold.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.termfold.app.R
import com.termfold.app.core.Folder
import com.termfold.app.ui.components.BareIconButton
import com.termfold.app.ui.components.BottomNavSpacer
import com.termfold.app.ui.components.ListRow
import com.termfold.app.ui.components.RowSpacer
import com.termfold.app.ui.theme.Palette
import com.termfold.app.ui.theme.TermFoldIcons
import com.termfold.app.ui.theme.folderTint

/** Screen 2: sessions inside one folder. */
@Composable
fun FolderDetailScreen(
    folder: Folder,
    onBack: () -> Unit,
    onOpenSession: (String) -> Unit,
    onAddSession: () -> Unit,
    onRenameFolder: () -> Unit,
    onOpenOptions: (String?) -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    filesOpen: Boolean = false,
    onToggleFiles: () -> Unit = {},
) {
    val edge = if (wide) 30.dp else 22.dp

    // Registry entries back the ACP session icons; the on-disk cache makes this instant after
    // the first successful load.
    val context = androidx.compose.ui.platform.LocalContext.current
    var registryAgents by remember { mutableStateOf(emptyList<com.termfold.app.acp.AcpAgent>()) }
    LaunchedEffect(Unit) {
        registryAgents = com.termfold.app.acp.AcpRegistry.all(context)
    }

    // Which sessions are working, refreshed twice a second: an ACP agent answering or setting
    // up, or a terminal whose program is producing output of its own.
    var working by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(folder.id, folder.sessions) {
        while (true) {
            working = folder.sessions.mapNotNull { session ->
                val key = com.termfold.app.shell.TerminalHost.sessionKey(folder.id, session.id)
                val busy = if (session.acpAgentId.isNotBlank()) {
                    com.termfold.app.acp.AcpSessions.client(key)?.state?.value?.let { state ->
                        state.agentBusy ||
                            state.phase == com.termfold.app.acp.AcpPhase.INSTALLING ||
                            state.phase == com.termfold.app.acp.AcpPhase.CONNECTING
                    } == true
                } else {
                    com.termfold.app.shell.TerminalHost.isWorking(key)
                }
                session.id.takeIf { busy }
            }.toSet()
            kotlinx.coroutines.delay(500)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BareIconButton(
                icon = TermFoldIcons.Back,
                contentDescription = stringResource(R.string.cd_back),
                onClick = onBack,
            )
            Spacer(Modifier.weight(1f))
            FilesButton(open = filesOpen, onClick = onToggleFiles)
            BareIconButton(
                icon = TermFoldIcons.More,
                contentDescription = stringResource(R.string.cd_more),
                onClick = { onOpenOptions(null) },
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = edge, end = if (wide) 26.dp else 16.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = TermFoldIcons.Folder,
                    contentDescription = null,
                    tint = folderTint(folder.tint),
                    modifier = Modifier.size(32.dp),
                )
            }

            Spacer(Modifier.size(14.dp))

            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleLarge,
                color = Palette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onRenameFolder),
            )

            com.termfold.app.ui.components.PrimaryButton(
                icon = TermFoldIcons.Plus,
                label = stringResource(R.string.new_session_title),
                onClick = onAddSession,
                compact = !wide,
            )
        }

        Spacer(Modifier.height(20.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = if (wide) 30.dp else 16.dp,
                end = if (wide) 26.dp else 16.dp,
            ),
        ) {
            items(folder.sessions, key = { it.id }) { session ->
                val agent = registryAgents.firstOrNull { it.id == session.acpAgentId }
                ListRow(
                    title = session.name,
                    leading = {
                        if (session.acpAgentId.isNotBlank()) {
                            Box(
                                modifier = Modifier.size(46.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                com.termfold.app.ui.components.AgentIconImage(
                                    iconUrl = agent?.iconUrl.orEmpty(),
                                    name = agent?.name ?: session.name,
                                    size = 34.dp,
                                )
                            }
                        } else {
                            SessionMarkCompact()
                        }
                    },
                    trailing = {
                        if (session.id in working) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.padding(end = 4.dp).size(16.dp),
                                strokeWidth = 2.dp,
                                color = Palette.Accent,
                            )
                        }
                        BareIconButton(
                            icon = TermFoldIcons.More,
                            contentDescription = stringResource(R.string.cd_more),
                            onClick = { onOpenOptions(session.id) },
                            size = 34,
                            tint = Palette.TextFaint,
                        )
                    },
                    highlighted = session.tint == 0,
                    onClick = { onOpenSession(session.id) },
                )
                RowSpacer()
            }
            item { BottomNavSpacer() }
        }
    }
}

/** Session rows use the terminal mark without the folder tint. */
@Composable
private fun SessionMarkCompact() {
    Box(
        modifier = Modifier.size(46.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = TermFoldIcons.Terminal,
            contentDescription = null,
            tint = Palette.Text,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Small menu shown as a sheet of rows for folder and session actions. */
@Composable
fun OptionsSheet(
    title: String,
    actions: List<Pair<String, () -> Unit>>,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Card,
        titleContentColor = Palette.Text,
        title = {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                actions.forEach { (label, action) ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onDismiss()
                                action()
                            }
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Palette.Text,
                        )
                    }
                }
            }
        },
        confirmButton = {},
    )
}

/** Confirmation dialog for destructive actions. */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Card,
        titleContentColor = Palette.Text,
        textContentColor = Palette.TextDim,
        title = { Text(text = title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(text = body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    onDismiss()
                    onConfirm()
                }
            ) {
                Text(
                    text = confirmLabel,
                    color = Palette.Accent,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.action_cancel),
                    color = Palette.TextDim,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    )
}


/** Small horizontal list of actions shown inside the setup card. */
@Composable
fun ActionRow(items: List<Pair<String, () -> Unit>>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items.forEach { (label, action) ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(Palette.Field)
                    .clickable(onClick = action),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Text,
                    maxLines = 1,
                )
            }
        }
    }
}
