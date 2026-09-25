package com.termfold.app.ui.screens

import android.content.ClipData
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termfold.app.R
import com.termfold.app.acp.AcpAgent
import com.termfold.app.acp.AcpBlock
import com.termfold.app.acp.AcpClient
import com.termfold.app.acp.AcpCommand
import com.termfold.app.acp.AcpSetting
import com.termfold.app.acp.AcpItem
import com.termfold.app.acp.AcpPendingPermission
import com.termfold.app.acp.AcpPhase
import com.termfold.app.acp.AcpRegistry
import com.termfold.app.acp.AcpSessions
import com.termfold.app.acp.AcpUiState
import com.termfold.app.acp.PastedImages
import com.termfold.app.core.Folder
import com.termfold.app.shell.ShellConfig
import com.termfold.app.shell.TerminalHost
import com.termfold.app.ui.components.AgentIconImage
import com.termfold.app.ui.components.BareIconButton
import com.termfold.app.ui.components.Markdown
import com.termfold.app.ui.theme.Mono
import com.termfold.app.ui.theme.Palette
import com.termfold.app.ui.theme.TermFoldIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ContentMaxWidth = 760.dp

/** A paste this long (or this many lines) becomes a text-file attachment instead of input. */
private const val LongPasteChars = 1_200
private const val LongPasteLines = 25

/** The user's side of the conversation: a quiet raised surface, not a coloured bubble. */
private val UserBubble = Color(0xFF1A1A1F)

/**
 * The native agent interface for ACP sessions: the agent runs inside the bundled Ubuntu
 * environment, and everything it says, thinks, and does renders as regular UI instead of
 * terminal output.
 */
@Composable
fun AgentSessionScreen(
    folder: Folder,
    sessionId: String,
    agentId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    filesOpen: Boolean = false,
    onToggleFiles: () -> Unit = {},
) {
    val context = LocalContext.current
    val key = TerminalHost.sessionKey(folder.id, sessionId)
    val guestDir = "/" + ShellConfig.guestWorkspaceName(folder.path)

    // The registry supplies the launch details and icon; its on-disk cache makes this instant
    // after the first load. Offline with no cache there is nothing to launch, so say so.
    var agent by remember { mutableStateOf<AcpAgent?>(null) }
    var registryAttempt by remember { mutableIntStateOf(0) }
    var registryFailed by remember { mutableStateOf(false) }
    LaunchedEffect(agentId, registryAttempt) {
        registryFailed = false
        agent = AcpRegistry.all(context, forceRefresh = registryAttempt > 0).firstOrNull { it.id == agentId }
        registryFailed = agent == null
    }

    val resolved = agent
    if (resolved == null) {
        Column(modifier = modifier.fillMaxSize()) {
            SessionHeader(title = agentId, subtitle = folder.name, iconUrl = "", onBack = onBack)
            CenterPanel {
                if (registryFailed) {
                    PanelTitle(stringResource(R.string.acp_agent_unavailable))
                    PanelBody(stringResource(R.string.acp_registry_offline))
                    Spacer(Modifier.height(16.dp))
                    PillButton(stringResource(R.string.action_retry), TermFoldIcons.Refresh) { registryAttempt++ }
                } else {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Palette.Accent)
                }
            }
        }
        return
    }

    val client = remember(agentId) {
        AcpSessions.client(key) ?: AcpSessions.put(
            key,
            AcpClient(
                context = context.applicationContext,
                sessionKey = key,
                agent = resolved,
                workspacePath = folder.path.takeIf { it.isNotBlank() },
                workspaceGuestDir = guestDir,
            ),
        )
    }

    LaunchedEffect(agentId) { client.start() }

    val state by client.state.collectAsStateWithLifecycle()
    var showHistory by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        SessionHeader(
            title = resolved.name,
            subtitle = folder.name + " · " + phaseLabel(state),
            iconUrl = resolved.iconUrl,
            onBack = onBack,
            live = state.agentBusy,
            filesOpen = filesOpen,
            onToggleFiles = onToggleFiles,
            onHistory = if (state.canResumeSessions && state.phase == AcpPhase.READY) {
                { showHistory = true }
            } else {
                null
            },
            onRestart = if (state.phase == AcpPhase.READY || state.phase == AcpPhase.ERROR) {
                { client.restart() }
            } else {
                null
            },
        )

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            val hasConversation = state.items.isNotEmpty() || state.restoring
            when {
                // Once there is a conversation it stays on screen; a restart or failure shows as a
                // slim banner above it rather than replacing what the user was reading.
                hasConversation -> Column(Modifier.fillMaxSize().widthIn(max = ContentMaxWidth)) {
                    PhaseBanner(state, onRetry = { client.start() })
                    Timeline(state = state, workspaceDir = guestDir, modifier = Modifier.weight(1f))
                }

                state.phase == AcpPhase.INSTALLING || state.phase == AcpPhase.CONNECTING ->
                    CenterPanel {
                        AgentIconImage(iconUrl = resolved.iconUrl, name = resolved.name, size = 52.dp, cornerRadius = 16.dp)
                        Spacer(Modifier.height(18.dp))
                        PanelTitle(stringResource(R.string.acp_setting_up, resolved.name))
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Palette.Accent)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = state.statusText.ifBlank { stringResource(R.string.acp_status_connecting) },
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.TextDim,
                            )
                        }
                        Spacer(Modifier.height(18.dp))
                        PanelBody(stringResource(R.string.acp_first_run_note))
                    }

                state.phase == AcpPhase.AUTH_REQUIRED -> CenterPanel {
                    AgentIconImage(iconUrl = resolved.iconUrl, name = resolved.name, size = 52.dp, cornerRadius = 16.dp)
                    Spacer(Modifier.height(18.dp))
                    PanelTitle(stringResource(R.string.acp_auth_required))
                    Spacer(Modifier.height(8.dp))
                    PanelBody(stringResource(R.string.acp_auth_body))
                    if (state.authMethods.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        PanelBody(stringResource(R.string.acp_auth_methods, state.authMethods.joinToString(", ")))
                    }
                    Spacer(Modifier.height(18.dp))
                    PillButton(stringResource(R.string.action_retry), TermFoldIcons.Refresh) { client.start() }
                }

                state.phase == AcpPhase.ERROR -> CenterPanel {
                    PanelTitle(stringResource(R.string.acp_error_title), color = Palette.Pink)
                    Spacer(Modifier.height(10.dp))
                    SelectionContainer {
                        Text(
                            text = state.errorDetail,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, fontSize = 12.sp),
                            color = Palette.TextDim,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(Palette.Card)
                                .padding(12.dp)
                                .heightIn(max = 180.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    PillButton(stringResource(R.string.action_retry), TermFoldIcons.Refresh) { client.start() }
                }

                else -> CenterPanel {
                    AgentIconImage(iconUrl = resolved.iconUrl, name = resolved.name, size = 52.dp, cornerRadius = 16.dp)
                    Spacer(Modifier.height(18.dp))
                    PanelTitle(stringResource(R.string.acp_ready_title, resolved.name))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = guestDir,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, fontSize = 12.sp),
                        color = Palette.TextFaint,
                    )
                }
            }
        }

        if (state.phase == AcpPhase.READY) {
            Composer(
                agentId = resolved.id,
                agentName = resolved.name,
                agentBusy = state.agentBusy,
                commands = state.commands,
                canResume = state.canResumeSessions,
                onResume = { showHistory = true },
                onNewSession = { client.startNewSession() },
                settings = state.settings,
                onChangeSetting = { setting, value -> client.changeSetting(setting, value) },
                onSend = { text, images, files -> client.send(text, images, files) },
                onCancel = { client.cancel() },
            )
        }
    }

    if (showHistory) {
        PastSessionsDialog(client = client, currentId = client.currentSessionId, onDismiss = { showHistory = false })
    }

    state.pendingPermission?.let { pending ->
        PermissionDialog(
            pending = pending,
            toolCall = state.items.lastOrNull {
                it is AcpItem.ToolCall && it.toolCallId == pending.toolCallId
            } as? AcpItem.ToolCall,
            onPick = { optionId -> client.respondPermission(pending.requestId, optionId) },
            onDeny = { client.respondPermission(pending.requestId, null) },
        )
    }
}

@Composable
private fun phaseLabel(state: AcpUiState): String = when {
    state.agentBusy -> stringResource(R.string.acp_working)
    state.phase == AcpPhase.READY -> stringResource(R.string.acp_phase_ready)
    state.phase == AcpPhase.AUTH_REQUIRED -> stringResource(R.string.acp_phase_signin)
    state.phase == AcpPhase.ERROR -> stringResource(R.string.acp_phase_stopped)
    state.phase == AcpPhase.CLOSED -> stringResource(R.string.acp_phase_stopped)
    else -> stringResource(R.string.acp_phase_starting)
}

@Composable
private fun SessionHeader(
    title: String,
    subtitle: String,
    iconUrl: String,
    onBack: () -> Unit,
    live: Boolean = false,
    filesOpen: Boolean = false,
    onToggleFiles: (() -> Unit)? = null,
    onHistory: (() -> Unit)? = null,
    onRestart: (() -> Unit)? = null,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 6.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BareIconButton(
                icon = TermFoldIcons.Back,
                contentDescription = stringResource(R.string.cd_back),
                onClick = onBack,
            )
            Spacer(Modifier.width(4.dp))
            Box {
                AgentIconImage(iconUrl = iconUrl, name = title, size = 32.dp, cornerRadius = 9.dp)
                if (live) {
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(Palette.Bg)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(Palette.Accent),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (onToggleFiles != null) FilesButton(open = filesOpen, onClick = onToggleFiles)
            if (onHistory != null) {
                BareIconButton(
                    icon = TermFoldIcons.History,
                    contentDescription = stringResource(R.string.acp_history),
                    onClick = onHistory,
                    tint = Palette.TextDim,
                    size = 40,
                )
            }
            if (onRestart != null) {
                BareIconButton(
                    icon = TermFoldIcons.Refresh,
                    contentDescription = stringResource(R.string.acp_restart),
                    onClick = onRestart,
                    tint = Palette.TextDim,
                    size = 40,
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(Palette.BorderSoft),
        )
    }
}

/** Shown above an existing conversation while the agent restarts or after it fails. */
@Composable
private fun PhaseBanner(state: AcpUiState, onRetry: () -> Unit) {
    val text: String
    val loading: Boolean
    when (state.phase) {
        AcpPhase.INSTALLING, AcpPhase.CONNECTING -> {
            text = state.statusText.ifBlank { stringResource(R.string.acp_status_connecting) }
            loading = true
        }
        AcpPhase.ERROR -> {
            text = state.errorDetail.ifBlank { stringResource(R.string.acp_error_title) }
            loading = false
        }
        AcpPhase.AUTH_REQUIRED -> {
            text = stringResource(R.string.acp_auth_required)
            loading = false
        }
        else -> if (state.restoring) {
            text = stringResource(R.string.acp_restoring)
            loading = true
        } else {
            return
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Card)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Palette.Accent)
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (state.phase == AcpPhase.ERROR) Palette.Pink else Palette.TextDim,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!loading) {
            Text(
                text = stringResource(R.string.action_retry),
                style = MaterialTheme.typography.labelLarge,
                color = Palette.Accent,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onRetry)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun CenterPanel(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content() }
    }
}

@Composable
private fun PanelTitle(text: String, color: Color = Palette.Text) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, color = color)
}

@Composable
private fun PanelBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = Palette.TextDim,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun PillButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Palette.Border, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = Palette.Text)
    }
}

// --- Timeline ---------------------------------------------------------------------------------

@Composable
private fun Timeline(state: AcpUiState, workspaceDir: String, modifier: Modifier = Modifier) {
    val items = state.items
    val listState = rememberLazyListState()

    // Follow the conversation as it grows, including a streaming reply growing in place.
    val lastLength = when (val last = items.lastOrNull()) {
        is AcpItem.AgentText -> last.text.length
        is AcpItem.Thought -> last.text.length
        else -> 0
    }
    LaunchedEffect(items.size, lastLength, state.agentBusy) {
        val count = listState.layoutInfo.totalItemsCount
        if (count > 0) runCatching { listState.animateScrollToItem(count - 1) }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items, key = { it.id }) { item ->
            val isLast = item === items.last()
            when (item) {
                is AcpItem.UserMessage -> UserMessageRow(item)
                is AcpItem.AgentText -> SelectionContainer { Markdown(item.text, Modifier.fillMaxWidth()) }
                is AcpItem.Thought -> ThoughtRow(item, live = isLast && state.agentBusy)
                is AcpItem.ToolCall -> ToolCallRow(item, workspaceDir)
                is AcpItem.Plan -> PlanCard(item)
                is AcpItem.Notice -> NoticeRow(item.text)
            }
        }

        if (state.agentBusy) {
            item(key = "working") { WorkingRow() }
        }
    }
}

@Composable
private fun UserMessageRow(item: AcpItem.UserMessage) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        if (item.images.isNotEmpty() || item.files.isNotEmpty()) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item.files.forEach { file -> FileChip(file) }
                item.images.forEach { image -> ImageThumb(image, size = 88) }
            }
            if (item.text.isNotBlank()) Spacer(Modifier.height(6.dp))
        }
        if (item.text.isNotBlank()) {
            SelectionContainer {
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.Text,
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 5.dp))
                        .background(UserBubble)
                        .border(
                            1.dp,
                            Palette.BorderSoft,
                            RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 5.dp),
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun ImageThumb(image: AcpBlock.Image, size: Int, onRemove: (() -> Unit)? = null) {
    val bitmap = remember(image.base64) {
        runCatching {
            val bytes = Base64.decode(image.base64, Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        }.getOrNull()
    }
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Card)
            .border(1.dp, Palette.BorderSoft, RoundedCornerShape(12.dp)),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (onRemove != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(0xCC08080A))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Icon(TermFoldIcons.Close, stringResource(R.string.acp_remove), tint = Palette.Text, modifier = Modifier.size(12.dp))
            }
        }
    }
}

@Composable
private fun FileChip(file: AcpBlock.TextFile, onClick: (() -> Unit)? = null, onRemove: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Card)
            .border(1.dp, Palette.BorderSoft, RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 10.dp, end = if (onRemove != null) 4.dp else 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TermFoldIcons.FileText, contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, fontSize = 12.sp),
                color = Palette.Text,
                maxLines = 1,
            )
            Text(
                text = stringResource(R.string.acp_pasted_file_meta, file.text.lines().size, file.text.length.toShortCount()),
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextFaint,
            )
        }
        if (onRemove != null) {
            Spacer(Modifier.width(2.dp))
            BareIconButton(
                icon = TermFoldIcons.Close,
                contentDescription = stringResource(R.string.acp_remove),
                onClick = onRemove,
                tint = Palette.TextFaint,
                size = 28,
            )
        }
    }
}

/**
 * What a tool-call row says: the agent's title, plus the file it works on when the title does
 * not already name it ("Read" becomes "Read  src/main.kt"). Paths inside the project are shown
 * relative to it.
 */
private fun toolLabel(item: AcpItem.ToolCall, workspaceDir: String): String {
    val path = item.paths.firstOrNull() ?: return item.title
    val shown = path.removePrefix(workspaceDir.trimEnd('/') + "/").ifBlank { path }
    val name = shown.substringAfterLast('/')
    if (item.title.contains(shown) || item.title.contains(name)) return item.title
    val more = if (item.paths.size > 1) " +${item.paths.size - 1}" else ""
    return "${item.title}  $shown$more"
}

/**
 * One Markdown line as ticker text: block markers (headings, bullets, quotes, fences) are dropped,
 * inline marks are kept for [com.termfold.app.ui.components.inline] to render.
 */
private fun tickerText(line: String): String =
    line.trim()
        // Markers need their trailing space, so the "**" of a bold line is not taken for a bullet.
        .replace(Regex("""^(#{1,6}\s+|[-*+]\s+|\d+[.)]\s+|>\s*|```\S*\s*)"""), "")
        .trim()

/** One line of the thinking ticker; [index] changes only when a new line begins. */
private data class TickerLine(val index: Int, val text: String)

/**
 * The agent's reasoning. Collapsed, it is a one-line ticker: the newest line slides in each time
 * the agent starts a new one. Expanded, the whole text shows and, while the agent is still
 * thinking, keeps the newest part in view as it streams.
 */
@OptIn(androidx.compose.animation.ExperimentalAnimationApi::class)
@Composable
private fun ThoughtRow(item: AcpItem.Thought, live: Boolean) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    val text = item.text.trim()
    val lines = text.lines().filter { it.isNotBlank() }
    val ticker = TickerLine(lines.size, lines.lastOrNull()?.trim().orEmpty())
    val scroll = rememberScrollState()

    LaunchedEffect(expanded, live) {
        if (expanded && live) {
            androidx.compose.runtime.snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (live) {
                PulsingText(stringResource(R.string.acp_thinking))
            } else {
                Text(
                    text = stringResource(R.string.acp_thought),
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextFaint,
                )
            }
            Spacer(Modifier.width(10.dp))
            if (!expanded) {
                androidx.compose.animation.AnimatedContent(
                    targetState = ticker,
                    contentKey = { it.index },
                    transitionSpec = {
                        (
                            androidx.compose.animation.slideInVertically { it / 2 } +
                                androidx.compose.animation.fadeIn()
                            ) togetherWith (
                            androidx.compose.animation.slideOutVertically { -it / 2 } +
                                androidx.compose.animation.fadeOut()
                            )
                    },
                    modifier = Modifier.weight(1f),
                    label = "thoughtTicker",
                ) { line ->
                    Text(
                        text = com.termfold.app.ui.components.inline(tickerText(line.text)),
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = if (expanded) TermFoldIcons.ChevronDown else TermFoldIcons.ChevronRight,
                contentDescription = null,
                tint = Palette.TextFaint,
                modifier = Modifier.size(14.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(Palette.Border))
                Spacer(Modifier.width(12.dp))
                // Reasoning is Markdown like the reply, rendered a step quieter so the answer
                // still reads as the main thing.
                Markdown(
                    text = text,
                    color = Palette.TextDim,
                    secondary = Palette.TextFaint,
                    bodyStyle = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(scroll),
                )
            }
        }
    }
}

/**
 * A tool call: what ran and how it went, with its output one tap away. Deliberately frameless;
 * a run of tool calls reads as a list of steps rather than a stack of boxes.
 */
@Composable
private fun ToolCallRow(item: AcpItem.ToolCall, workspaceDir: String) {
    var open by remember(item.id) { mutableStateOf(false) }
    val hasDetail = item.detail.isNotBlank()
    val failed = item.status == "failed"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = hasDetail) { open = !open }
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = toolIcon(item.kind),
                contentDescription = null,
                tint = if (failed) Palette.Pink else Palette.TextFaint,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = toolLabel(item, workspaceDir),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, fontSize = 12.5.sp),
                color = if (failed) Palette.Pink else Palette.TextDim,
                maxLines = if (open) 4 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            when (item.status) {
                "completed" -> Icon(TermFoldIcons.Check, null, tint = Palette.Green, modifier = Modifier.size(14.dp))
                "failed" -> Icon(TermFoldIcons.Close, null, tint = Palette.Pink, modifier = Modifier.size(14.dp))
                else -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Palette.Accent)
            }
            if (hasDetail) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = if (open) TermFoldIcons.ChevronDown else TermFoldIcons.ChevronRight,
                    contentDescription = null,
                    tint = Palette.TextFaint,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        if (open && hasDetail) {
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Text(
                    text = item.detail.trim(),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp),
                    color = Palette.TermOut,
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Palette.Card)
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(10.dp),
                )
            }
        }
    }
}

/** The agent's plan, replaced in place as steps complete. */
@Composable
private fun PlanCard(item: AcpItem.Plan) {
    val done = item.entries.count { it.status == "completed" }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, Palette.BorderSoft, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.acp_plan),
                style = MaterialTheme.typography.labelLarge,
                color = Palette.Text,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$done/${item.entries.size}",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
                color = Palette.TextFaint,
            )
        }
        Spacer(Modifier.height(8.dp))
        item.entries.forEach { entry ->
            Row(
                modifier = Modifier.padding(vertical = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.padding(top = 3.dp).size(14.dp), contentAlignment = Alignment.Center) {
                    when (entry.status) {
                        "completed" -> Icon(TermFoldIcons.Check, null, tint = Palette.Green, modifier = Modifier.size(14.dp))
                        "in_progress" -> Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.Accent))
                        else -> Box(Modifier.size(9.dp).clip(CircleShape).border(1.3.dp, Palette.TextFaint, CircleShape))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = entry.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (entry.status) {
                        "completed" -> Palette.TextFaint
                        "in_progress" -> Palette.Text
                        else -> Palette.TextDim
                    },
                )
            }
        }
    }
}

@Composable
private fun NoticeRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Pink.copy(alpha = 0.07f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(Palette.Pink))
        Spacer(Modifier.width(10.dp))
        SelectionContainer {
            Text(text = text, style = MaterialTheme.typography.bodySmall, color = Palette.Text)
        }
    }
}

/** Three stepping dots and the time spent, so a long turn never looks frozen. */
@Composable
private fun WorkingRow() {
    var seconds by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = System.currentTimeMillis()
        while (true) {
            seconds = (System.currentTimeMillis() - start) / 1_000
            delay(1_000)
        }
    }
    val transition = rememberInfiniteTransition(label = "working")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1_200)),
        label = "dots",
    )
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val lit = phase.toInt() == index
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(if (lit) Palette.Accent else Palette.Border),
            )
            Spacer(Modifier.width(4.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.acp_working) + if (seconds >= 3) " · ${formatElapsed(seconds)}" else "",
            style = MaterialTheme.typography.labelSmall,
            color = Palette.TextFaint,
        )
    }
}

private fun formatElapsed(seconds: Long): String =
    if (seconds < 60) "${seconds}s" else "${seconds / 60}m ${seconds % 60}s"

@Composable
private fun PulsingText(label: String) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulseAlpha",
    )
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = Palette.Accent,
        modifier = Modifier.alpha(alpha),
    )
}

// --- Permission -------------------------------------------------------------------------------

@Composable
private fun PermissionDialog(
    pending: AcpPendingPermission,
    toolCall: AcpItem.ToolCall?,
    onPick: (String) -> Unit,
    onDeny: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDeny,
        containerColor = Palette.Card,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                text = stringResource(R.string.acp_permission_title),
                style = MaterialTheme.typography.titleMedium,
                color = Palette.Text,
            )
        },
        text = {
            Column {
                if (toolCall != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Palette.Bg)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(toolIcon(toolCall.kind), null, tint = Palette.TextDim, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = toolCall.title,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, fontSize = 12.5.sp),
                            color = Palette.Text,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                }
                pending.options.forEach { option ->
                    // Every option reads in white; only "allow once" is filled, as the default.
                    Text(
                        text = option.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .then(
                                if (option.kind == "allow_once") {
                                    Modifier.background(Palette.Accent)
                                } else {
                                    Modifier.border(1.dp, Palette.Border, RoundedCornerShape(12.dp))
                                },
                            )
                            .clickable { onPick(option.id) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {},
    )
}

// --- Composer ---------------------------------------------------------------------------------

/**
 * The prompt composer. Text is typed or pasted; images arrive from the picker, the clipboard, or
 * straight from the keyboard's image and GIF panels; a long paste is turned into a `.txt`
 * attachment instead of flooding the field, and goes to the agent as a file.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Composer(
    agentId: String,
    agentName: String,
    agentBusy: Boolean,
    commands: List<AcpCommand>,
    canResume: Boolean,
    onResume: () -> Unit,
    onNewSession: () -> Unit,
    settings: List<AcpSetting>,
    onChangeSetting: (AcpSetting, String) -> Unit,
    onSend: (String, List<AcpBlock.Image>, List<AcpBlock.TextFile>) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val input = rememberTextFieldState()
    val images = remember { mutableStateListOf<AcpBlock.Image>() }
    val files = remember { mutableStateListOf<AcpBlock.TextFile>() }
    var pasteCounter by remember { mutableIntStateOf(0) }
    var preview by remember { mutableStateOf<AcpBlock.TextFile?>(null) }

    fun addImage(uri: android.net.Uri) {
        scope.launch {
            val encoded = withContext(Dispatchers.IO) { PastedImages.load(context, uri) }
            if (encoded != null) images.add(encoded.toBlock())
        }
    }

    fun addTextFile(text: String) {
        pasteCounter++
        files.add(AcpBlock.TextFile("pasted-$pasteCounter.txt", text))
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) addImage(uri)
    }

    val canSend = input.text.isNotBlank() || images.isNotEmpty() || files.isNotEmpty()

    // The slash menu: open while the text is "/" plus a partial command name, and nothing else.
    // The app's own commands come first (resuming is the app's job, not every agent's), then
    // whatever the agent published.
    val builtIns = buildList {
        if (canResume) add(AcpCommand(BUILTIN_RESUME, stringResource(R.string.acp_cmd_resume), ""))
        add(AcpCommand(BUILTIN_NEW, stringResource(R.string.acp_cmd_new), ""))
    }
    val allCommands = builtIns + commands.filter { c -> builtIns.none { it.name == c.name } }
    val typed = input.text.toString()
    // Esc closes the menu until the text changes again.
    var dismissedFor by remember { mutableStateOf<String?>(null) }
    val slashMatches = if (typed.startsWith("/") && typed.none { it.isWhitespace() } && dismissedFor != typed) {
        val query = typed.drop(1).lowercase()
        allCommands.filter { it.name.lowercase().startsWith(query) } +
            allCommands.filter { !it.name.lowercase().startsWith(query) && it.name.lowercase().contains(query) }
    } else {
        emptyList()
    }
    // The highlighted row, moved with the arrow keys; back to the top whenever the text changes.
    var highlighted by remember(typed) { mutableIntStateOf(0) }
    if (highlighted >= slashMatches.size) highlighted = 0

    fun pickCommand(command: AcpCommand) {
        when (command.name) {
            BUILTIN_RESUME -> { input.clearText(); onResume() }
            BUILTIN_NEW -> { input.clearText(); onNewSession() }
            else -> input.setTextAndPlaceCursorAtEnd("/${command.name} ")
        }
    }
    fun send() {
        if (!canSend || agentBusy) return
        onSend(input.text.toString().trim(), images.toList(), files.toList())
        input.clearText()
        images.clear()
        files.clear()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Palette.Card)
                .border(1.dp, Palette.Border, RoundedCornerShape(20.dp)),
        ) {
            if (slashMatches.isNotEmpty()) {
                SlashMenu(slashMatches, highlighted = highlighted, onPick = ::pickCommand)
            }

            if (images.isNotEmpty() || files.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 10.dp, end = 10.dp, top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    files.forEach { file ->
                        FileChip(file, onClick = { preview = file }, onRemove = { files.remove(file) })
                    }
                    images.forEach { image ->
                        ImageThumb(image, size = 56, onRemove = { images.remove(image) })
                    }
                }
            }

            // Text first, full width; the controls sit in a toolbar beneath it, with the
            // model/reasoning control and Send together in the bottom-right corner.
            BasicTextField(
                state = input,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Palette.Text),
                cursorBrush = SolidColor(Palette.Accent),
                // A text area: three lines tall from the start so it never jumps while typing,
                // growing to eight, then scrolling inside instead of pushing the chat away.
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 3, maxHeightInLines = 8),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)
                    // Pastes and keyboard content arrive here before they reach the text.
                    // Images are taken as attachments; a long text paste becomes a file.
                    .contentReceiver { content ->
                        if (content.hasMediaType(MediaType.Image) || content.hasMediaType(MediaType.Text)) {
                            content.consume { item: ClipData.Item ->
                                val uri = item.uri
                                val text = item.text?.toString()
                                when {
                                    uri != null && PastedImages.isImage(context, uri) -> {
                                        addImage(uri); true
                                    }
                                    text != null && (text.length > LongPasteChars || text.count { it == '\n' } >= LongPasteLines) -> {
                                        addTextFile(text); true
                                    }
                                    else -> false
                                }
                            }
                        } else {
                            content
                        }
                    }
                    // A hardware keyboard sends with Enter; Shift+Enter keeps a newline. While the
                    // slash menu is open the arrows move through it, Enter or Tab picks the
                    // highlighted command, and Esc closes it.
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val menuOpen = slashMatches.isNotEmpty()
                        when {
                            menuOpen && event.key == Key.DirectionDown -> {
                                highlighted = (highlighted + 1) % slashMatches.size; true
                            }
                            menuOpen && event.key == Key.DirectionUp -> {
                                highlighted = (highlighted - 1 + slashMatches.size) % slashMatches.size; true
                            }
                            menuOpen && (event.key == Key.Tab || (event.key == Key.Enter && !event.isShiftPressed)) -> {
                                pickCommand(slashMatches[highlighted]); true
                            }
                            menuOpen && event.key == Key.Escape -> {
                                dismissedFor = typed; true
                            }
                            event.key == Key.Enter && !event.isShiftPressed -> {
                                send(); true
                            }
                            else -> false
                        }
                    },
                decorator = { inner ->
                    Box {
                        if (input.text.isEmpty()) {
                            Text(
                                text = stringResource(R.string.acp_message_hint, agentName),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Palette.TextFaint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )


            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BareIconButton(
                    icon = TermFoldIcons.Image,
                    contentDescription = stringResource(R.string.acp_attach_image),
                    onClick = {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    tint = Palette.TextDim,
                    size = 38,
                )
                Spacer(Modifier.weight(1f))
                if (settings.isNotEmpty()) {
                    SettingsButton(agentId, settings, onChangeSetting)
                    Spacer(Modifier.width(4.dp))
                }
                val active = agentBusy || canSend
                Box(
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (active) Palette.Accent else Palette.CardPressed)
                        .clickable(enabled = active) { if (agentBusy) onCancel() else send() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (agentBusy) TermFoldIcons.Stop else TermFoldIcons.Send,
                        contentDescription = stringResource(if (agentBusy) R.string.acp_stop else R.string.cd_send),
                        tint = if (active) Palette.OnAccent else Palette.TextFaint,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }

    preview?.let { file ->
        AlertDialog(
            onDismissRequest = { preview = null },
            containerColor = Palette.Card,
            shape = RoundedCornerShape(20.dp),
            title = {
                Text(file.name, style = MaterialTheme.typography.titleMedium.copy(fontFamily = Mono), color = Palette.Text)
            },
            text = {
                SelectionContainer {
                    Text(
                        text = file.text,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, fontSize = 12.sp),
                        color = Palette.TermOut,
                        modifier = Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                Text(
                    text = stringResource(R.string.action_close),
                    style = MaterialTheme.typography.labelLarge,
                    color = Palette.Accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { preview = null }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            },
        )
    }
}

/** Picker order: what people change most comes first. */
private fun settingRank(setting: AcpSetting): Int = when (setting.category) {
    "model" -> 0
    "thought_level" -> 1
    "mode" -> 2
    "model_config" -> 3
    else -> 4
}

/** Above this many choices the popup gets a search field. */
private const val SearchThreshold = 8

/** How many recently picked models are remembered per agent. */
private const val RecentModels = 5

/** Recently picked model values for one agent, newest first, kept in app preferences. */
private fun recentModels(context: android.content.Context, agentId: String): List<String> =
    context.getSharedPreferences("acp", android.content.Context.MODE_PRIVATE)
        .getString("recent_models_$agentId", "").orEmpty()
        .split('\n').filter { it.isNotBlank() }

private fun rememberModel(context: android.content.Context, agentId: String, value: String) {
    val list = (listOf(value) + recentModels(context, agentId).filter { it != value }).take(RecentModels)
    context.getSharedPreferences("acp", android.content.Context.MODE_PRIVATE)
        .edit().putString("recent_models_$agentId", list.joinToString("\n")).apply()
}

/** "openai/gpt-5" -> "openai"; ids without a provider prefix have none. */
private fun providerOf(value: String): String? =
    value.substringBefore('/', missingDelimiterValue = "").ifBlank { null }

/**
 * The session-settings control in the input bar: the sliders icon plus a short reading of the
 * current model and reasoning level, opening a popup with every setting the agent offers.
 *
 * Long model lists (Pi and OpenCode list every model of every provider they can reach) get a
 * search field, provider grouping, and a Recent section with what the user actually picks.
 */
@Composable
private fun SettingsButton(
    agentId: String,
    settings: List<AcpSetting>,
    onChange: (AcpSetting, String) -> Unit,
) {
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    /** The setting whose full list is open, by id; null shows the overview. */
    var page by remember { mutableStateOf<String?>(null) }
    var recent by remember(agentId) { mutableStateOf(recentModels(context, agentId)) }
    val ordered = settings.sortedBy(::settingRank)
    val summary = listOfNotNull(
        settings.firstOrNull { it.category == "model" }?.currentLabel,
        settings.firstOrNull { it.category == "thought_level" }?.currentLabel,
    ).joinToString(" · ")

    fun pick(setting: AcpSetting, value: String) {
        onChange(setting, value)
        if (setting.category == "model") {
            rememberModel(context, agentId, value)
            recent = recentModels(context, agentId)
        }
        open = false
        page = null
        query = ""
    }

    Box {
        Row(
            modifier = Modifier
                .padding(vertical = 2.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable { open = true }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                TermFoldIcons.Sliders,
                contentDescription = stringResource(R.string.acp_settings),
                tint = if (open) Palette.Accent else Palette.TextDim,
                modifier = Modifier.size(18.dp),
            )
            if (summary.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                    color = Palette.TextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 200.dp),
                )
            }
        }

        androidx.compose.material3.DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false; page = null; query = "" },
            containerColor = Palette.Card,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .widthIn(min = 300.dp, max = 400.dp)
                .heightIn(max = 520.dp),
        ) {
            val listPage = page?.let { id -> ordered.firstOrNull { it.id == id } }
            if (listPage == null) {
                // Overview: every setting at a glance. Short lists are one-tap chips; the model
                // (and any other long list) is a single row that opens its own page, so the
                // settings after it never sit below a hundred models.
                ordered.forEachIndexed { index, setting ->
                    if (index > 0) SettingsDivider()
                    when {
                        setting.isToggle -> {
                            SettingsHeader(setting.name)
                            val on = setting.current.toBoolean()
                            ChoiceChips(
                                choices = listOf(
                                    com.termfold.app.acp.AcpChoice("false", stringResource(R.string.acp_setting_off)),
                                    com.termfold.app.acp.AcpChoice("true", stringResource(R.string.acp_setting_on)),
                                ),
                                current = on.toString(),
                                onPick = { value -> onChange(setting, value) },
                            )
                        }

                        opensAsPage(setting) -> Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { page = setting.id; query = "" }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = setting.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Palette.TextFaint,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = setting.currentLabel,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                                    color = Palette.Text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                text = "${setting.choices.size}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
                                color = Palette.TextFaint,
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(TermFoldIcons.ChevronRight, null, tint = Palette.TextFaint, modifier = Modifier.size(16.dp))
                        }

                        else -> {
                            SettingsHeader(setting.name)
                            ChoiceChips(
                                choices = setting.choices,
                                current = setting.current,
                                onPick = { value -> onChange(setting, value) },
                            )
                            val description = setting.choices.firstOrNull { it.value == setting.current }?.description
                            if (!description.isNullOrBlank()) {
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Palette.TextFaint,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
                                )
                            }
                        }
                    }
                }
            } else {
                // The list page: back to the overview, search, recent picks, provider groups.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BareIconButton(
                        icon = TermFoldIcons.Back,
                        contentDescription = stringResource(R.string.cd_back),
                        onClick = { page = null; query = "" },
                        tint = Palette.TextDim,
                        size = 36,
                    )
                    Text(
                        text = listPage.name,
                        style = MaterialTheme.typography.labelLarge,
                        color = Palette.Text,
                    )
                }
                if (listPage.choices.size > SearchThreshold) {
                    SettingsSearchField(query = query, onQuery = { query = it })
                }

                val needle = query.trim().lowercase()
                val shown = listPage.choices.filter { choice ->
                    needle.isEmpty() ||
                        choice.name.lowercase().contains(needle) ||
                        choice.value.lowercase().contains(needle) ||
                        choice.description.lowercase().contains(needle)
                }
                val isModel = listPage.category == "model"

                val recentChoices = if (isModel && needle.isEmpty() && listPage.choices.size > SearchThreshold) {
                    recent.mapNotNull { value -> listPage.choices.firstOrNull { it.value == value } }
                } else {
                    emptyList()
                }
                if (recentChoices.isNotEmpty()) {
                    SettingsSubHeader(stringResource(R.string.acp_setting_recent))
                    recentChoices.forEach { choice ->
                        SettingRow(choice.name, choice.description, choice.value == listPage.current) { pick(listPage, choice.value) }
                    }
                }

                val groups = shown.groupBy { providerOf(it.value) }
                if (isModel && groups.size > 1) {
                    groups.forEach { (provider, choices) ->
                        SettingsSubHeader(provider ?: stringResource(R.string.acp_setting_other))
                        choices.forEach { choice ->
                            SettingRow(choice.name, choice.description, choice.value == listPage.current) { pick(listPage, choice.value) }
                        }
                    }
                } else {
                    shown.forEach { choice ->
                        SettingRow(choice.name, choice.description, choice.value == listPage.current) { pick(listPage, choice.value) }
                    }
                }
                if (shown.isEmpty()) {
                    Text(
                        text = stringResource(R.string.acp_setting_no_match),
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextFaint,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/** Models always get their own page; so does any other setting too long for a row of chips. */
private fun opensAsPage(setting: AcpSetting): Boolean =
    setting.category == "model" || setting.choices.size > 6

/** A setting's choices as a row of one-tap chips (reasoning level, mode, on/off). */
@Composable
private fun ChoiceChips(
    choices: List<com.termfold.app.acp.AcpChoice>,
    current: String,
    onPick: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        choices.forEach { choice ->
            val selected = choice.value == current
            Text(
                text = choice.name,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                color = if (selected) Palette.OnAccent else Palette.TextDim,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) Palette.Accent else Palette.Bg)
                    .clickable { onPick(choice.value) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SettingsSearchField(query: String, onQuery: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Palette.Bg)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TermFoldIcons.Search, contentDescription = null, tint = Palette.TextFaint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, color = Palette.Text),
            cursorBrush = SolidColor(Palette.Accent),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.acp_setting_search),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                            color = Palette.TextFaint,
                        )
                    }
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            Icon(
                TermFoldIcons.Close,
                contentDescription = null,
                tint = Palette.TextFaint,
                modifier = Modifier
                    .size(16.dp)
                    .clickable { onQuery("") },
            )
        }
    }
}

@Composable
private fun SettingsHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Palette.TextFaint,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingsSubHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = Mono),
        color = Palette.Accent.copy(alpha = 0.8f),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun SettingsDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(1.dp)
            .background(Palette.BorderSoft),
    )
}

@Composable
private fun SettingRow(label: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                color = if (selected) Palette.Text else Palette.TextDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextFaint,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Icon(TermFoldIcons.Check, contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(16.dp))
        }
    }
}

/** Commands the app handles itself rather than sending to the agent. */
private const val BUILTIN_RESUME = "resume"
private const val BUILTIN_NEW = "new"

/**
 * The slash commands matching what is typed. Tapping one picks it; with a keyboard the arrows
 * move the highlight, which is kept on screen.
 */
@Composable
private fun SlashMenu(matches: List<AcpCommand>, highlighted: Int, onPick: (AcpCommand) -> Unit) {
    val listState = rememberLazyListState()
    // Scroll only as far as needed to keep the highlighted row on screen.
    LaunchedEffect(highlighted, matches.size) {
        if (matches.isEmpty()) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        val viewportEnd = listState.layoutInfo.viewportEndOffset
        val fully = visible.filter { it.offset >= 0 && it.offset + it.size <= viewportEnd }
        val first = fully.firstOrNull()?.index ?: 0
        val last = fully.lastOrNull()?.index ?: 0
        when {
            highlighted < first -> listState.animateScrollToItem(highlighted)
            highlighted > last -> listState.animateScrollToItem((highlighted - (fully.size - 1)).coerceAtLeast(0))
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp)
            .padding(top = 6.dp, start = 6.dp, end = 6.dp),
    ) {
        items(matches.size) { index ->
            val command = matches[index]
            val active = index == highlighted
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (active) Palette.AccentSoft else Color.Transparent)
                    .clickable { onPick(command) }
                    .padding(horizontal = 10.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "/" + command.name,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = Mono, fontSize = 13.sp),
                    color = Palette.Accent,
                    maxLines = 1,
                )
                if (command.description.isNotBlank() || command.hint.isNotBlank()) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = command.description.ifBlank { command.hint },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (active) Palette.TextDim else Palette.TextFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .height(1.dp)
            .background(Palette.BorderSoft),
    )
}

/**
 * The agent's earlier sessions in this folder. Picking one reopens it (with its history when the
 * agent replays it); "New session" starts over.
 */
@Composable
private fun PastSessionsDialog(
    client: AcpClient,
    currentId: String?,
    onDismiss: () -> Unit,
) {
    var sessions by remember { mutableStateOf<List<com.termfold.app.acp.AcpPastSession>?>(null) }
    LaunchedEffect(Unit) { sessions = client.listPastSessions() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Card,
        shape = RoundedCornerShape(20.dp),
        title = { Text(stringResource(R.string.acp_history_title), style = MaterialTheme.typography.titleMedium, color = Palette.Text) },
        text = {
            Column(Modifier.heightIn(max = 460.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Palette.Bg)
                        .clickable { client.startNewSession(); onDismiss() }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(TermFoldIcons.Plus, null, tint = Palette.Accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.acp_cmd_new), style = MaterialTheme.typography.labelLarge, color = Palette.Text)
                }
                Spacer(Modifier.height(10.dp))
                val list = sessions
                when {
                    list == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.Accent)
                    }
                    list.isEmpty() -> Text(
                        stringResource(R.string.acp_history_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextFaint,
                        modifier = Modifier.padding(8.dp),
                    )
                    else -> LazyColumn {
                        items(list.size) { i ->
                            val past = list[i]
                            val current = past.sessionId == currentId
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable(enabled = !current) { client.openPastSession(past.sessionId); onDismiss() }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                            ) {
                                Text(
                                    text = past.title.ifBlank { stringResource(R.string.acp_history_untitled) },
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp),
                                    color = if (current) Palette.Accent else Palette.Text,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val meta = listOfNotNull(
                                    relativeTime(past.updatedAt).ifBlank { null },
                                    if (current) stringResource(R.string.acp_history_current) else null,
                                ).joinToString(" · ")
                                if (meta.isNotBlank()) {
                                    Text(meta, style = MaterialTheme.typography.labelSmall, color = Palette.TextFaint)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
    )
}

/** "3 min ago", "yesterday", "12 Sep" from an ISO-8601 timestamp; blank if unparseable. */
private fun relativeTime(iso: String): String {
    val then = runCatching { java.time.Instant.parse(iso) }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(iso).toInstant() }.getOrNull()
        ?: return ""
    val minutes = java.time.Duration.between(then, java.time.Instant.now()).toMinutes()
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        minutes < 48 * 60 -> "yesterday"
        minutes < 7 * 24 * 60 -> "${minutes / (24 * 60)} days ago"
        else -> java.time.format.DateTimeFormatter.ofPattern("d MMM")
            .format(then.atZone(java.time.ZoneId.systemDefault()))
    }
}

private fun Int.toShortCount(): String =
    if (this >= 1_000) "${"%.1f".format(this / 1_000f)}k" else toString()

private fun toolIcon(kind: String): ImageVector = when (kind) {
    "read" -> TermFoldIcons.FileText
    "edit", "delete", "move" -> TermFoldIcons.Pencil
    "search", "fetch" -> TermFoldIcons.Search
    "execute", "command" -> TermFoldIcons.Terminal
    "think" -> TermFoldIcons.ChevronRight
    else -> TermFoldIcons.Gear
}

